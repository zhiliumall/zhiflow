/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.convert.Convert;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.admin.service.GoodsSpuSpecService;
import com.zhiflow.mall.admin.service.ShopInfoService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.SpuSpecDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.GoodsSpuSpec;
import com.zhiflow.mall.common.entity.ShopInfo;
import lombok.RequiredArgsConstructor;
import lombok.SneakyThrows;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * spu商品
 *
 * <p>单店铺、无审核流程：保存即过审。商品写库后由 {@link GoodsSearchIndexSync} 在事务提交后写搜索索引。
 *
 * @date 2019-08-12 16:25:10
 */
@Service
@RequiredArgsConstructor
public class GoodsSpuServiceImpl extends ServiceImpl<GoodsSpuMapper, GoodsSpu> implements GoodsSpuService {

	private final GoodsSkuService goodsSkuService;
	private final GoodsSpuSpecService goodsSpuSpecService;
	private final GoodsSkuSpecValueService goodsSkuSpecValueService;
	private final ShopInfoService shopInfoService;
	private final GoodsSearchIndexSync goodsSearchIndexSync;
	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean removeById(Serializable id) {
		// 删之前查：软删之后就查不到商品行（和它的 tenant_id）了
		Map<String, String> cached = goodsDetailCacheEviction.ofSpus(List.of(String.valueOf(id)));
		super.removeById(id);
		//删除SpuSpec、SkuSpecValue、Sku
		goodsSpuSpecService.remove(Wrappers.<GoodsSpuSpec>update().lambda()
				.eq(GoodsSpuSpec::getSpuId, id));
		goodsSkuSpecValueService.remove(Wrappers.<GoodsSkuSpecValue>update().lambda()
				.eq(GoodsSkuSpecValue::getSpuId, id));
		goodsSkuService.remove(Wrappers.<GoodsSku>update().lambda()
				.eq(GoodsSku::getSpuId, id));
		goodsSearchIndexSync.deleteAfterCommit(String.valueOf(id));
		goodsDetailCacheEviction.evictAfterCommit(cached);
		return true;
	}

	@SneakyThrows
	@Override
	public IPage<GoodsSpu> page1(IPage<GoodsSpu> page, GoodsSpu goodsSpu) {
		return baseMapper.selectPage1(page, goodsSpu);
	}

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public void shelf(String shelf, String ids) {
		GoodsSpu goodsSpu = new GoodsSpu();
		goodsSpu.setShelf(shelf);
		List<String> idList = Convert.toList(String.class, ids);
		baseMapper.update(goodsSpu,Wrappers.<GoodsSpu>lambdaQuery()
				.in(GoodsSpu::getId, idList));
		// 提交后清商品详情缓存，并同步搜索索引（上下架都 upsert：索引里带 shelf 字段，C 端按它过滤）
		goodsDetailCacheEviction.evictAfterCommit(goodsDetailCacheEviction.ofSpus(idList));
		for (String s : ids.split(",")) {
			goodsSearchIndexSync.upsertAfterCommit(s);
		}
	}

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean save1(GoodsSpu goodsSpu) {
		requireShop(goodsSpu);
		this.saveHanddler(goodsSpu);
		goodsSearchIndexSync.upsertAfterCommit(goodsSpu.getId());
		goodsDetailCacheEviction.evictAfterCommit(goodsDetailCacheEviction.ofSpus(Collections.singletonList(goodsSpu.getId())));
		return true;
	}

	/**
	 * 新增商品必须落到一个本租户可见的店铺上（单店铺：表单没传 shopId 就取默认店铺）。
	 * <p>传了但店铺不存在时返回明确的业务错误，而不是 NPE 500。
	 */
	void requireShop(GoodsSpu goodsSpu) {
		if (StrUtil.isBlank(goodsSpu.getShopId())) {
			goodsSpu.setShopId(CommonConstants.DEFAULT_SHOP_ID);
		}
		ShopInfo shopInfo = shopInfoService.getById(goodsSpu.getShopId());
		if (shopInfo == null) {
			String name = StrUtil.isBlank(goodsSpu.getName()) ? "" : "【" + goodsSpu.getName() + "】";
			throw new BizException("商品" + name + "所选店铺不存在或已删除，请重新选择店铺");
		}
	}

	/**
	 * 商品编码：新增必填；编辑时请求里带了才校验（不带表示不改）。库上有 (tenant_id, spu_code) 的唯一索引，
	 * 不先查就会撞约束变成 500。
	 */
	void checkSpuCode(GoodsSpu goodsSpu, boolean creating) {
		String code = goodsSpu.getSpuCode();
		if (code == null && !creating) {
			return;
		}
		if (StrUtil.isBlank(code)) {
			throw new BizException("请填写商品编码");
		}
		code = code.trim();
		goodsSpu.setSpuCode(code);
		boolean taken = baseMapper.exists(Wrappers.<GoodsSpu>lambdaQuery()
				.eq(GoodsSpu::getSpuCode, code)
				.ne(!creating && StrUtil.isNotBlank(goodsSpu.getId()), GoodsSpu::getId, goodsSpu.getId()));
		if (taken) {
			throw new BizException("商品编码【" + code + "】已被其他商品使用");
		}
	}

	void saveHanddler(GoodsSpu goodsSpu){
		checkSpuCode(goodsSpu, true);
		// 单店铺无审核流程：保存即过审。C 端只展示 verify_status='1' 的商品
		goodsSpu.setVerifyStatus(MallConstants.GOODS_STATUS_1);
		goodsSpu.setPriceDown(null);
		goodsSpu.setPriceUp(null);
		if (StrUtil.isBlank(goodsSpu.getFreightType())) {
			goodsSpu.setFreightType(MallConstants.FREIGHT_TYPE_0);
		}
		baseMapper.insert(goodsSpu);
		List<GoodsSku> listGoodsSku = goodsSpu.getSkus();
		if(listGoodsSku !=null && listGoodsSku.size()>0){
			//新增sku
			listGoodsSku.forEach(goodsSku -> {
				priceUpDown(goodsSpu,goodsSku);
				goodsSku.setShopId(goodsSpu.getShopId());
				goodsSku.setSpuId(goodsSpu.getId());
				goodsSkuService.save(goodsSku);
			});
			baseMapper.updateById(goodsSpu);
			if(MallConstants.SPU_SPEC_TYPE_1.equals(goodsSpu.getSpecType())){//多规格处理
				requireSpuSpec(goodsSpu);
				addSpec(goodsSpu);
			}
		}
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public R saveBatch1(List<GoodsSpu> listGoodsSpu) {
		for (GoodsSpu item : listGoodsSpu) {
			requireShop(item);
			this.saveHanddler(item);
			goodsSearchIndexSync.upsertAfterCommit(item.getId());
		}
		goodsDetailCacheEviction.evictAfterCommit(goodsDetailCacheEviction.ofSpus(
				listGoodsSpu.stream().map(GoodsSpu::getId).toList()));
		return R.ok();
	}

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean updateById1(GoodsSpu goodsSpu) {
		GoodsSpu oldGoodsSpu = baseMapper.selectById(goodsSpu.getId());
		if (oldGoodsSpu == null) {
			throw new BizException("商品不存在或已被删除");
		}
		goodsDetailCacheEviction.evictAfterCommit(oldGoodsSpu);
		checkSpuCode(goodsSpu, false);
		requireSpuSpec(goodsSpu);
		goodsSpu.setPriceDown(null);
		goodsSpu.setPriceUp(null);
		List<GoodsSku> listGoodsSku = goodsSpu.getSkus();
		if (listGoodsSku != null && listGoodsSku.size() > 0) {
            // 只删「本次提交里已不存在」的旧 sku，本次要复用的 id 必须留在库里。
            //
            // 不能像以前那样先按 spu_id 全删再逐条 saveOrUpdate：S8 软删翻转
            // （BaseEntity.delFlag 加 @TableLogic，d3fabc3e8 / 2026-07-26）之后 remove() 只置
            // del_flag=1，物理行还在；紧接着的 saveOrUpdate() 走 selectById 被 @TableLogic 追加
            // AND del_flag='0'，查不到刚被墓碑化的那行 → 判定「新增」→ 拿前端回传的老 sku id
            // INSERT → goods_sku_pkey 冲突 → 整个编辑事务回滚。表现是所有带 sku 的商品编辑
            // 一律返回「修改商品失败，请稍后重试」（2026-08-08 定位，goods_sku 自 07-30 起零成功写入）。
            Set<String> keepSkuIds = listGoodsSku.stream()
                    .map(GoodsSku::getId)
                    .filter(StringUtils::isNotBlank)
                    .collect(Collectors.toSet());
            goodsSkuService.remove(Wrappers.<GoodsSku>update().lambda()
                    .eq(GoodsSku::getSpuId, goodsSpu.getId())
                    .notIn(!keepSkuIds.isEmpty(), GoodsSku::getId, keepSkuIds));
            //新增sku
            listGoodsSku.forEach(goodsSku -> {
                priceUpDown(goodsSpu, goodsSku);
                goodsSku.setShopId(goodsSpu.getShopId());
                goodsSku.setSpuId(goodsSpu.getId());
                goodsSku.setTenantId(goodsSpu.getTenantId());
				if (StringUtils.isNotBlank(goodsSku.getId())) {
					// 丢掉客户端回传的 version，不参与 @Version 乐观锁 CAS。
					//
					// goods_sku 有 @Version，且 OptimisticLockerInnerInterceptor 是全局注册的。
					// 上面改成真 UPDATE 之后 CAS 第一次真正生效，但客户端并不可靠地回传这个字段：
					// 读接口 getById1 明确返回了 skus[].version（实测 =1），商家端提交回来却是 0
					// → CAS 恒不匹配 → 所有编辑改为 100% 报「库存已被其他操作更新」，等于把一种
					// 全线失败换成另一种。三端都可能这么干，后端不能依赖客户端回传。
					//
					// 置 null 后 MP 跳过 CAS 也不 +1，version 由「下单扣减/回补」那条原子 SQL 独占
					// 维护。代价是：编辑表单里的 stock 会覆盖库存现值，表单停留期间的扣减会被写回
					// ——这与 2026-07-26 之前（全删重插同样按表单值落库）的行为一致，不是新增风险。
					// 要彻底消除，应让编辑接口不再写 stock、库存只走专用调整入口（见 controller 里
					// 已有的 GoodsStockAdjustmentDTO），那是业务口径变更，另行决策。
					goodsSku.setVersion(null);
					// ⚠️ 这里必须是 updateById，不能是 saveOrUpdate。
					// MyBatis-Plus 3.5.16 的 AbstractRepository.saveOrUpdate(T) 实现是
					//     return getBaseMapper().insertOrUpdate(entity);
					// ——直接打到 mapper，既不走子类对 save/updateById 的重写，也不经过 Spring 代理。
					// 而 GoodsSkuServiceImpl.updateById 上挂着
					//     @DistributedLock(name="GoodsSku", keys={"#entity.getId()"}, FAIL_FAST)
					// 走 saveOrUpdate 等于这把锁根本没生效：两个后台同时编辑同一个 SKU 会交叉写库存。
					// 本分支已经在 id 非空里，本来就只可能是更新，直接调 updateById 最省事也最安全。
					if (!goodsSkuService.updateById(goodsSku)) {//更新库存
						throw new BizException("商品规格【" + StrUtil.blankToDefault(goodsSku.getSkuCode(), goodsSku.getId())
								+ "】更新失败，请刷新后重新提交");
					}
				} else {
					goodsSkuService.save(goodsSku);
					goodsSku.setId(goodsSku.getId());
				}
            });
            baseMapper.updateById(goodsSpu);
            //统一删除SpuSpec、SkuSpecValue
            goodsSpuSpecService.remove(Wrappers.<GoodsSpuSpec>update().lambda()
                    .eq(GoodsSpuSpec::getSpuId, goodsSpu.getId()));
            goodsSkuSpecValueService.remove(Wrappers.<GoodsSkuSpecValue>update().lambda()
                    .eq(GoodsSkuSpecValue::getSpuId, goodsSpu.getId()));
            if (MallConstants.SPU_SPEC_TYPE_1.equals(goodsSpu.getSpecType())) {//多规格处理
                addSpec(goodsSpu);
            }
        }
        goodsSearchIndexSync.upsertAfterCommit(goodsSpu.getId());
        return true;
    }

	@SneakyThrows
	@Override
	public boolean updateById2(GoodsSpu goodsSpu) {
		GoodsSpu oldGoodsSpu = baseMapper.selectById(goodsSpu.getId());
		if (oldGoodsSpu == null) {
			throw new BizException("商品不存在或已被删除");
		}
		baseMapper.updateById(goodsSpu);
		goodsSearchIndexSync.upsertAfterCommit(goodsSpu.getId());
		// 不带事务：写库已自动提交，这里当场清
		goodsDetailCacheEviction.evictAfterCommit(oldGoodsSpu);
		return true;
	}

	@Override
	public GoodsSpu getById1(String id) {
		GoodsSpu goodsSpu = baseMapper.selectById1(id);
		// id 不存在时返回 null 交给上层判空（前端提示「商品不存在」而不是 500）
		if (goodsSpu == null) {
			return null;
		}
		// 组装categoryId数组
		List<String> categoryId = new ArrayList<>();
		if (StrUtil.isNotBlank(goodsSpu.getCategoryFirst())) {
			categoryId.add(goodsSpu.getCategoryFirst());
		}
		if (StrUtil.isNotBlank(goodsSpu.getCategorySecond())) {
			categoryId.add(goodsSpu.getCategorySecond());
		}
		if (StrUtil.isNotBlank(goodsSpu.getCategoryThird())) {
			categoryId.add(goodsSpu.getCategoryThird());
		}
		goodsSpu.setCategoryId(categoryId);
		return goodsSpu;
	}

	/**
	 * 落库商品的规格树。
	 *
	 * 两处 distinct 不是可选的优化，是防复发的闸门（2026-09-07 出过事故）：
	 * 本方法把「客户端回传什么就存什么」，而回传内容来自编辑页的回读。回读的几条手写 XML
	 * 曾漏掉 del_flag 过滤，把墓碑一并吐给前端，前端原样回传，这里再全量落库 ——
	 * 每保存一次规格行数就翻一倍（实测 goods_sku_spec_value 全表行数是实际所需的近 30 倍）。读侧已补过滤，这里是第二道：即使旧标签页、缓存表单
	 * 或其它端再送来重复项，也只落一份。
	 * 数据库侧另有 (spu_id, spec_id) / (sku_id, spec_value_id) 的部分唯一索引兜底。
	 */
	/**
	 * 多规格商品没带规格定义（spuSpec）时，从各 sku 自带的规格项按首次出现顺序推出来——落库只用到规格 id。
	 * 详情接口不返回 spuSpec，「先查详情再原样回存」的调用方就会走到这里；以前这里直接空指针。
	 * sku 上也没有规格项时才报错：否则下面会删光规格树再按空列表重建，等于把商品的规格清掉。
	 */
	void requireSpuSpec(GoodsSpu goodsSpu) {
		boolean multi = MallConstants.SPU_SPEC_TYPE_1.equals(goodsSpu.getSpecType());
		List<GoodsSku> skus = goodsSpu.getSkus();
		if (!multi || skus == null || skus.isEmpty() || (goodsSpu.getSpuSpec() != null && !goodsSpu.getSpuSpec().isEmpty())) {
			return;
		}
		List<SpuSpecDTO> derived = skus.stream()
				.filter(sku -> sku.getSpecs() != null)
				.flatMap(sku -> sku.getSpecs().stream())
				.map(GoodsSkuSpecValue::getSpecId)
				.filter(StringUtils::isNotBlank)
				.distinct()
				.map(specId -> {
					SpuSpecDTO dto = new SpuSpecDTO();
					dto.setId(specId);
					return dto;
				})
				.collect(Collectors.toList());
		if (derived.isEmpty()) {
			throw new BizException("多规格商品的规格不能为空");
		}
		goodsSpu.setSpuSpec(derived);
	}

	void addSpec(GoodsSpu goodsSpu){
		//新增SpuSpec（按 specId 去重）
		List<GoodsSpuSpec> listGoodsSpuSpec = goodsSpu.getSpuSpec().stream()
			.map(SpuSpecDTO::getId)
			.filter(Objects::nonNull)
			.distinct()
			.map(specId->{
				GoodsSpuSpec goodsSpuSpec = new GoodsSpuSpec();
				goodsSpuSpec.setSpuId(goodsSpu.getId());
				goodsSpuSpec.setSpecId(specId);
				return goodsSpuSpec;
			}).collect(Collectors.toList());
		for(int i = 0; i<listGoodsSpuSpec.size(); i++) {
			listGoodsSpuSpec.get(i).setSort(i);//设置排序字段
		}
		goodsSpuSpecService.saveBatch(listGoodsSpuSpec);
		//新增SkuSpecValue（按 skuId + specValueId 去重）
		List<GoodsSkuSpecValue> listGoodsSkuSpecValue = new ArrayList<>();
		Set<String> seenSkuSpecValue = new HashSet<>();
		goodsSpu.getSkus().forEach(goodsSku -> {
			if (goodsSku.getSpecs() == null) {
				return;
			}
			goodsSku.getSpecs().forEach(goodsSkuSpecValue -> {
				if(!seenSkuSpecValue.add(goodsSku.getId() + "#" + goodsSkuSpecValue.getSpecValueId())){
					return;
				}
				// 编辑时回传的是旧行（带主键）：旧行刚被软删、物理行还在，原样插入会撞主键，一律按新行插
				goodsSkuSpecValue.setId(null);
				goodsSkuSpecValue.setCreateTime(null);
				goodsSkuSpecValue.setUpdateTime(null);
				goodsSkuSpecValue.setDelFlag(null);
				goodsSkuSpecValue.setSpuId(goodsSpu.getId());
				goodsSkuSpecValue.setSkuId(goodsSku.getId());
				listGoodsSkuSpecValue.add(goodsSkuSpecValue);
			});
		});
		for(int i = 0; i<listGoodsSkuSpecValue.size(); i++) {
			listGoodsSkuSpecValue.get(i).setSort(i);//设置排序字段
		}
		goodsSkuSpecValueService.saveBatch(listGoodsSkuSpecValue);
	}

	/**
	 * 获取商品最高最低价
	 *
     * @param goodsSpu
	 * @param goodsSku
	 */
	void priceUpDown(GoodsSpu goodsSpu,GoodsSku goodsSku){
		if(CommonConstants.YES.equals(goodsSku.getEnable())){
			BigDecimal priceDown = goodsSpu.getPriceDown();
			if(priceDown == null || priceDown.compareTo(goodsSku.getSalesPrice()) == 1){
				goodsSpu.setPriceDown(goodsSku.getSalesPrice());
			}
			BigDecimal priceUp = goodsSpu.getPriceUp();
			if(priceUp == null || priceUp.compareTo(goodsSku.getSalesPrice()) == -1){
				goodsSpu.setPriceUp(goodsSku.getMarketPrice());
			}
		}
	}
}
