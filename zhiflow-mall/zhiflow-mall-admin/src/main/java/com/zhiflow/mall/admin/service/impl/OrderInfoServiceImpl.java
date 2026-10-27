/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.api.mall.OrderInfoApi;
import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.support.ExpressCompanyDict;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.mall.admin.mapper.GoodsSkuMapper;
import com.zhiflow.mall.admin.mapper.GoodsSkuSpecValueMapper;
import com.zhiflow.mall.admin.mapper.OrderInfoMapper;
import com.zhiflow.mall.admin.mapper.OrderItemMapper;
import com.zhiflow.mall.admin.service.*;
import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.OrderDeliveryDTO;
import com.zhiflow.mall.common.dto.OrderTotalDTO;
import com.zhiflow.mall.common.dto.OrderRemarkBatchRequest;
import com.zhiflow.mall.common.dto.OrderRemarkBatchResult;
import com.zhiflow.mall.common.entity.*;
import com.zhiflow.mall.common.enums.*;
import com.zhiflow.mall.common.orderno.OrderNoGenerator;
import com.zhiflow.api.pay.ZhiflowPayApi;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 商城订单
 *
 * @date 2019-09-10 15:21:22
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderInfoServiceImpl extends ServiceImpl<OrderInfoMapper, OrderInfo> implements OrderInfoService {

	private final OrderItemService orderItemService;
	/** 运单号长度上限，与 {@code order_logistics.logistics_no varchar(30)} 一致。 */
	static final int MAX_LOGISTICS_NO_LENGTH = 30;

	// perf 2026-09-15：page1 批量装配 listOrderItem 用，见该方法注释
	private final OrderItemMapper orderItemMapper;
	private final OrderRecordService orderRecordService;
	private final OrderNoGenerator orderNoGenerator;
	private final OrderLogisticsService orderLogisticsService;
	private final RedisTemplate<String, String> redisTemplate;
	private final MallConfigProperties mallConfigProperties;
	private final ExpressCompanyDict expressCompanyDict;
	private final GoodsSkuMapper goodsSkuMapper;
	private final GoodsSkuSpecValueMapper goodsSkuSpecValueMapper;
	private final GoodsSpuService goodsSpuService;
	private final ShopInfoService shopInfoService;
	private final ZhiflowPayApi zhiflowPayApi;
	private final WxUserApi wxUserHttpClient;
	private final OrderInfoApi orderInfoApiHttpClient;
	/** 支付状态机 v1.2 阶段 1：订单状态的唯一写入口（7.1）。 */
	private final com.zhiflow.mall.common.trade.statemachine.order.OrderStateMachine orderStateMachine;

	/**
	 * 支付状态机阶段 0 · 0.4：发货前的状态校验。
	 *
	 * <p>此前本方法从头到尾不读 status / is_pay，只有前端按钮挡着：已取消、已全退、未付的单都能被改成「待收货」，
	 * 7 天后被自动收货，还会发成长值和收货奖励。规则：
	 * <ul>
	 *   <li>必须已付款；</li>
	 *   <li>待发货（1）：正常发货；</li>
	 *   <li>待收货（2）：只允许修改已有物流单（带 orderLogisticsId）；</li>
	 *   <li>其他状态一律拒绝。</li>
	 * </ul>
	 */
	static void assertDeliverable(OrderInfo orderInfo, String orderLogisticsId) {
		if (orderInfo == null) {
			throw new BizException("订单不存在");
		}
		if (!CommonConstants.YES.equals(orderInfo.getIsPay())) {
			throw new BizException("订单未支付，不能发货");
		}
		String status = orderInfo.getStatus();
		if (OrderInfoEnum.STATUS_1.getValue().equals(status)) {
			return;
		}
		if (OrderInfoEnum.STATUS_2.getValue().equals(status) && StrUtil.isNotBlank(orderLogisticsId)) {
			return;
		}
		throw new BizException("订单当前状态不能发货");
	}

	/**
	 * O10 发货：全部商品已发（或未发的都已全额退款）时走状态机进待收货，同时写 delivery_time（7 天自动收货按它起算）。
	 * 已是待收货（修改已有物流单）= 幂等无操作；其他状态被拒抛业务异常（{@link #assertDeliverable} 之外的第二道）。
	 */
	private void fireShip(OrderInfo orderInfo) {
		com.zhiflow.mall.common.trade.statemachine.Transition<com.zhiflow.mall.common.trade.statemachine.order.OrderState> t = orderStateMachine.fire(orderInfo.getId(),
				com.zhiflow.mall.common.trade.statemachine.order.OrderEvent.SHIP,
				com.zhiflow.mall.common.trade.statemachine.order.OrderTransitionContext.builder()
						.operatorType(com.zhiflow.mall.common.trade.statemachine.Operator.ADMIN)
						.operatorId(currentOperatorId())
						.reason("后台发货")
						.patch(com.zhiflow.mall.common.trade.statemachine.order.OrderPatch.builder().deliveryTime(java.time.OffsetDateTime.now()).build())
						.build());
		if (t.isRejected()) {
			throw new BizException("订单当前状态不能发货：" + t.reason());
		}
		com.zhiflow.mall.common.trade.statemachine.order.OrderStateSync.apply(orderInfo, com.zhiflow.mall.common.trade.statemachine.order.OrderState.PENDING_RECEIVE);
	}

	/**
	 * 本次发货的商品项。不传 = 本单全部 NORMAL 项；传了的必须都属于本单（原来只按 id 更新，
	 * 别的单的项也会被改）。没发过货的已退款 / 已取消项<b>跳过</b>（不把退了钱的货记成发出）——后台发货页一直把整单
	 * 全部商品项都传上来，拒绝会让「退过一件的单」再也发不了货，所以是跳过不是拒绝。已发过的项可以重复列出（修改物流单）；
	 * 跳过之后一项都不剩才拒。
	 */
	static List<String> resolveShipItems(List<OrderItem> itemsOfOrder, String requested) {
		if (StrUtil.isBlank(requested)) {
			List<String> normal = itemsOfOrder.stream()
					.filter(i -> com.zhiflow.mall.common.trade.item.ItemStatus.of(i) == com.zhiflow.mall.common.trade.item.ItemStatus.NORMAL)
					.map(OrderItem::getId).toList();
			if (normal.isEmpty()) {
				throw new BizException("没有待发货的商品");
			}
			return normal;
		}
		Map<String, OrderItem> byId = itemsOfOrder.stream().collect(Collectors.toMap(OrderItem::getId, i -> i, (a, b) -> a));
		List<String> ids = new ArrayList<>();
		for (String id : StrUtil.split(requested, ',', true, true)) {
			OrderItem i = byId.get(id);
			if (i == null) {
				throw new BizException("商品不属于该订单");
			}
			com.zhiflow.mall.common.trade.item.ItemStatus st = com.zhiflow.mall.common.trade.item.ItemStatus.of(i);
			if (!com.zhiflow.mall.common.trade.item.ItemStatus.shipped(i)
					&& st == com.zhiflow.mall.common.trade.item.ItemStatus.CANCELLED) {
				log.info("发货跳过已取消的商品项 orderItemId={} itemStatus={}", id, st);
				continue;
			}
			ids.add(id);
		}
		if (ids.isEmpty()) {
			throw new BizException("没有待发货的商品");
		}
		return ids;
	}

	private static String currentOperatorId() {
		try {
			var user = com.zhiflow.common.security.util.SecurityUtils.getUser();
			return user == null || user.getId() == null ? null : String.valueOf(user.getId());
		}
		catch (Exception e) {
			return null;
		}
	}

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void delivery(OrderDeliveryDTO orderDeliveryDTO) {
		OrderInfo orderInfo = baseMapper.selectById(orderDeliveryDTO.getOrderId());
		assertDeliverable(orderInfo, orderDeliveryDTO.getOrderLogisticsId());
		// 本次发的商品项（不传 = 全部待发 NORMAL 项），先校验再调快递接口
		List<OrderItem> itemsOfOrder = orderItemService.list(Wrappers.<OrderItem>lambdaQuery()
				.eq(OrderItem::getOrderId, orderInfo.getId()));
		List<String> shipItemIds = resolveShipItems(itemsOfOrder, orderDeliveryDTO.getOrderItemIds());
		OrderLogistics orderLogistics = new OrderLogistics();
		orderLogistics.setId(orderDeliveryDTO.getOrderLogisticsId());
		orderLogistics.setRecName(orderDeliveryDTO.getRecName());
		orderLogistics.setRecTel(orderDeliveryDTO.getRecTel());
		orderLogistics.setRecAddress(orderDeliveryDTO.getRecAddress());
		orderLogistics.setOrderId(orderDeliveryDTO.getOrderId());
		OrderItem orderItem = new OrderItem();
		orderItem.setDeliveryTime(LocalDateTime.now());
		orderItem.setDeliveryType(orderDeliveryDTO.getDeliveryType());
		//修改发货则重置该订单物流id绑定的订单详情
		if(StrUtil.isNotBlank(orderLogistics.getId())){
			orderItemService.update(new UpdateWrapper<OrderItem>().lambda()
					.set(OrderItem::getLogisticsId,null)
					.eq(OrderItem::getLogisticsId,orderLogistics.getId()));
		}
		if(MallConstants.DELIVERY_TYPE_1.equals(orderDeliveryDTO.getDeliveryType())){//发物流
			if (StrUtil.isBlank(orderDeliveryDTO.getLogisticsCode()) || StrUtil.isBlank(orderDeliveryDTO.getLogisticsNo())) {
				throw new BizException("请填写物流公司和运单号");
			}
			// 与 order_logistics.logistics_no varchar(30) 一致，超长直接拒，不让它撞库变成 500
			if (orderDeliveryDTO.getLogisticsNo().trim().length() > MAX_LOGISTICS_NO_LENGTH) {
				throw new BizException("运单号最多 " + MAX_LOGISTICS_NO_LENGTH + " 个字符");
			}
			orderLogistics.setLogisticsCode(orderDeliveryDTO.getLogisticsCode());
			orderLogistics.setLogisticsName(expressCompanyDict.requireName(orderDeliveryDTO.getLogisticsCode()));
			orderLogistics.setLogisticsNo(orderDeliveryDTO.getLogisticsNo().trim());
			orderLogisticsService.saveOrUpdate(orderLogistics);
			//订单详情更新订单物流ID
			orderItem.setLogisticsId(orderLogistics.getId());
		}
		//更新订单详情（只动本单的项）
		orderItemService.update(orderItem,Wrappers.<OrderItem>lambdaQuery()
				.eq(OrderItem::getOrderId, orderInfo.getId()).in(OrderItem::getId, shipItemIds));
		// 本次发的项 NORMAL → SHIPPED、记本项发货时刻（同一事务）
		orderItemService.markItemsShipped(orderInfo.getId(), shipItemIds);
		// O10 按商品项汇总：没有 NORMAL 项且至少一项 SHIPPED → 进待收货；部分发货仍待发货（前端显示「已发 x / n」）
		com.zhiflow.mall.common.trade.item.ItemStatus.Summary items = com.zhiflow.mall.common.trade.item.ItemStatus
				.summarize(orderItemService.list(Wrappers.<OrderItem>lambdaQuery().eq(OrderItem::getOrderId, orderInfo.getId())));
		if (items.allShippable()) {
			//更新订单状态（支付状态机 v1.2 O10：状态机写 status 与 delivery_time）
			fireShip(orderInfo);
			//加入redis，在订单详情中会查询过期时间，用于获取自动收货倒计时
			String keyRedis = String.valueOf(StrUtil.format("{}:{}::{}", TenantContextHolder.getTenantId(), CacheConstants.MALL_ORDER_KEY_STATUS_2, orderInfo.getId()));
			redisTemplate.opsForValue().set(keyRedis, orderInfo.getOrderNo() , MallConstants.ORDER_TIME_OUT_2, TimeUnit.DAYS);//设置过期时间
		}
		// 支付状态机 v1.2 阶段 1：原来这里 super.updateById(orderInfo) 整行写回（发货只改了 status），
		// 状态改由 fireShip 在锁内条件更新；不再整行写回旧快照（v1.0 2.2-10 那一类）。

	}

	@Override
	public IPage<OrderInfo> page1(IPage<OrderInfo> page, Wrapper<OrderInfo> queryWrapper) {
		IPage<OrderInfo> result = baseMapper.selectPage1(page, queryWrapper.getEntity());
		// perf 2026-09-15：批量装配 listOrderItem，替代 orderInfoMap2 里逐行的
		// OrderItemMapper.selectList2 嵌套 select（每页 20 条订单原是 1+20 条 SQL，现固定 2 条）。
		// 空/缺失行为与嵌套 select 对齐：无订单项 -> 空 list（非 null）。
		List<OrderInfo> orders = result.getRecords();
		if (orders != null && !orders.isEmpty()) {
			List<String> orderIds = orders.stream().map(OrderInfo::getId).collect(Collectors.toList());
			Map<String, List<OrderItem>> itemsByOrderId = new HashMap<>();
			for (OrderItem item : orderItemMapper.selectListByOrderIds(orderIds)) {
				itemsByOrderId.computeIfAbsent(item.getOrderId(), k -> new ArrayList<>()).add(item);
			}
			for (OrderInfo order : orders) {
				order.setListOrderItem(itemsByOrderId.getOrDefault(order.getId(), new ArrayList<>()));
			}
		}
		return result;
	}

	/**
	 * 统计订单金额和数量
	 * @param userId 用户id
	 * @return
	 */
	@Override
	public OrderTotalDTO sumOrderAmountAndCountByUserId(String userId) {
		OrderTotalDTO result = baseMapper.sumOrderAmountAndCountByUserId(userId);
		return result;
	}

	@Override
	public Map<String, Long> getOrderCountsByStatus(String shopId) {
		List<Map<String, Object>> results = baseMapper.selectOrderCountsByStatus(shopId);
		Map<String, Long> countMap = new HashMap<>();
		
		// 将查询结果转换为 Map<状态, 数量>
		for (Map<String, Object> result : results) {
			String status = String.valueOf(result.get("status"));
			Long count = Convert.toLong(result.get("count"), 0L);
			countMap.put(status, count);
		}
		
		// 确保所有状态都有值（即使为0）
		countMap.putIfAbsent("0", 0L);
		countMap.putIfAbsent("1", 0L);
		countMap.putIfAbsent("2", 0L);
		countMap.putIfAbsent("3", 0L);
		countMap.putIfAbsent("4", 0L);
		countMap.putIfAbsent("5", 0L);
		countMap.putIfAbsent("6", 0L);
		countMap.putIfAbsent("7", 0L);
		countMap.putIfAbsent("10", 0L);

		return countMap;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean removeById(Serializable id) {
		orderItemService.remove(Wrappers.<OrderItem>lambdaQuery()
				.eq(OrderItem::getOrderId,id));//删除订单详情
		return super.removeById(id);
	}

	@Override
	public BigDecimal sumPaymentPrice(OrderInfo orderInfo) {
		return baseMapper.sumPaymentPrice(orderInfo);
	}

	@Override
	public OrderInfo getById1(Serializable id) {
		return baseMapper.selectById1(id);
	}

	@Override
	public OrderRemarkBatchResult batchRemark(OrderRemarkBatchRequest request, String shopId) {
		List<String> orderIds = request.getOrderIds().stream().filter(Objects::nonNull).map(String::trim)
			.filter(id -> !id.isEmpty()).collect(Collectors.toCollection(LinkedHashSet::new)).stream().toList();
		OrderRemarkBatchResult result = new OrderRemarkBatchResult();
		result.setRequested(orderIds.size());
		List<OrderRemarkBatchResult.Item> items = new ArrayList<>(orderIds.size());
		for (String orderId : orderIds) {
			OrderRemarkBatchResult.Item item = new OrderRemarkBatchResult.Item();
			item.setOrderId(orderId);
			try {
				OrderInfo order = baseMapper.selectById(orderId);
				if (order == null || CommonConstants.STATUS_DEL.equals(order.getDelFlag())
						|| (StrUtil.isNotBlank(shopId) && !shopId.equals(order.getShopId()))) {
					item.setStatus("FAILED");
					item.setCode("ORDER_NOT_FOUND_OR_FORBIDDEN");
					item.setMessage("订单不存在或不可操作");
					result.setFailed(result.getFailed() + 1);
				} else if (Objects.equals(order.getRemark(), request.getRemark())) {
					item.setStatus("UNCHANGED");
					item.setRemark(order.getRemark());
					result.setUnchanged(result.getUnchanged() + 1);
				} else {
					OrderInfo update = new OrderInfo();
					update.setId(orderId);
					update.setRemark(request.getRemark());
					if (updateById(update)) {
						item.setStatus("UPDATED");
						item.setRemark(request.getRemark());
						result.setUpdated(result.getUpdated() + 1);
					} else {
						item.setStatus("FAILED");
						item.setCode("ORDER_UPDATE_FAILED");
						item.setMessage("订单备注更新失败，请重试");
						result.setFailed(result.getFailed() + 1);
					}
				}
			} catch (RuntimeException ex) {
				item.setStatus("FAILED");
				item.setCode("ORDER_UPDATE_FAILED");
				item.setMessage("订单备注更新失败，请重试");
				result.setFailed(result.getFailed() + 1);
			}
			items.add(item);
		}
		result.setResults(items);
		return result;
	}
}
