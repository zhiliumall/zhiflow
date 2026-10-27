/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.log.context.LogRecordContext;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.mall.admin.service.*;
import com.zhiflow.mall.common.dto.OrderTotalDTO;
import com.zhiflow.mall.common.dto.UserInfoFullVO;
import com.zhiflow.mall.common.dto.UserInfoSimpleVO;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.mall.common.entity.UserAddress;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.mall.admin.support.WxContractDisplayMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 商城用户
 *
 * @date 2019-12-04 11:09:55
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/userinfo")
@Tag(description = "userinfo", name = "商城用户管理")
public class UserInfoController {

    private final UserInfoService userInfoService;
    private final WxUserApi wxUserHttpClient;
    private final OrderInfoService orderInfoService;
    private final UserAddressService userAddressService;

	/**
     * 分页查询
     * @param page 分页对象
     * @param userInfo 商城用户
     * @return
     */
	@Operation(summary = "分页查询")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('mall:userinfo:index')")
    public R getUserinfoPage(Page page, UserInfo userInfo) {
        IPage<UserInfo> result = userInfoService.page1(page, userInfo);
        return R.ok(result);
    }

	/**
	 * 查询数量
	 * @param userInfo
	 * @return
	 */
	@Operation(summary = "查询数量")
	@GetMapping("/count")
	public R getCount(UserInfo userInfo) {
		return R.ok(userInfoService.count(Wrappers.query(userInfo)));
	}

    /**
     * 通过id查询商城用户
     * @param id
     * @return R
     */
    @Operation(summary = "通过id查询商城用户")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:userinfo:get')")
	public R getById(@PathVariable("id") String id) {
		UserInfo userInfo = userInfoService.getById(id);
		if (userInfo == null) {
			return R.failed("用户不存在");
		}
		fillAddressFallback(userInfo);
		// 设置完整手机号（不脱敏）
		userInfo.setFullPhone(userInfo.getPhone());
		// 只有当appId不为空时才查询微信用户信息
		if (userInfo.getAppId() != null) {
			try {
				R<WxUserInsideDTO> wxUserR = wxUserHttpClient.getByMallUserId(id, userInfo.getAppId(), SecurityConstants.FROM_IN);
				if (wxUserR != null && wxUserR.isOk() && wxUserR.getData() != null) {
					userInfo.setWxUser(WxContractDisplayMapper.toDisplayEntity(wxUserR.getData()));
				}
			} catch (Exception e) {
				log.error("查询用户微信信息失败, userId: {}, error: {}", id, e.getMessage(), e);
			}
		}
		OrderTotalDTO orderTotal = orderInfoService.sumOrderAmountAndCountByUserId(userInfo.getId());
		if (orderTotal != null) {
			orderTotal.setAvgOrderAmount(calcAvgOrderAmount(orderTotal.getTotalAmount(), orderTotal.getTotalCount()));
		}
		userInfo.setOrderTotal(orderTotal);
		return R.ok(userInfo);
    }

	/**
	 * 客单价（AOV）= 累计已支付金额 / 累计已支付订单数，2 位小数 HALF_UP；订单数为空或 0 时返回 0。
	 */
	private BigDecimal calcAvgOrderAmount(BigDecimal totalAmount, Integer totalCount) {
		if (totalAmount == null || totalCount == null || totalCount == 0) {
			return BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP);
		}
		return totalAmount.divide(BigDecimal.valueOf(totalCount), 2, RoundingMode.HALF_UP);
	}

	private void fillAddressFallback(UserInfo userInfo) {
		if (userInfo == null) {
			return;
		}
		if (hasText(userInfo.getProvince()) && hasText(userInfo.getCity()) && hasText(userInfo.getAddress())) {
			return;
		}
		UserAddress fallbackAddress = userAddressService.lambdaQuery()
			.eq(UserAddress::getUserId, userInfo.getId())
			.eq(UserAddress::getDelFlag, "0")
			.orderByDesc(UserAddress::getIsDefault)
			.orderByDesc(UserAddress::getUpdateTime)
			.orderByDesc(UserAddress::getCreateTime)
			.last("limit 1")
			.one();
		if (fallbackAddress == null) {
			return;
		}
		if (!hasText(userInfo.getProvince())) {
			userInfo.setProvince(fallbackAddress.getProvinceName());
		}
		if (!hasText(userInfo.getCity())) {
			userInfo.setCity(fallbackAddress.getCityName());
		}
		if (!hasText(userInfo.getAddress())) {
			userInfo.setAddress(fallbackAddress.getDetailInfo());
		}
	}

	private boolean hasText(String value) {
		return value != null && !value.trim().isEmpty();
	}

    /**
     * 新增商城用户
     * @param userInfo 商城用户
     * @return R
     */
	@Operation(summary = "新增商城用户")
    @SysLog("新增商城用户")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:userinfo:add')")
    public R save(@RequestBody UserInfo userInfo) {
        return R.ok(userInfoService.save(userInfo));
    }

    /**
     * 修改商城用户
     * @param userInfo 商城用户
     * @return R
     */
	@Operation(summary = "修改商城用户")
    @SysLog("修改商城用户")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:userinfo:edit')")
    public R updateById(@RequestBody UserInfo userInfo) {
		userInfo.setPhone(null);
		userInfoService.updateById(userInfo);
		return R.ok();
    }

    /**
     * 通过id删除商城用户
     * @param id
     * @return R
     */
	@Operation(summary = "通过id删除商城用户")
    // content 只用昵称、不拼手机号：content 是自由文本，掩码链对它不可靠
    @SysLog(value = "删除商城用户", content = "'删除商城用户「' + #user.nickName + '」'")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:userinfo:del')")
    public R removeById(@PathVariable String id) {
        LogRecordContext.put("user", userInfoService.getById(id));
        return R.ok(userInfoService.removeById(id));
    }

	/**
	 * 查询统计
	 * @param userInfo
	 * @return
	 */
	@Operation(summary = "查询统计")
	@GetMapping("/statistics")
	public R getStatistics(UserInfo userInfo) {
		//总数量
		long countTotal = userInfoService.count(Wrappers.query(userInfo));
		//今天数量
		LocalDate localDate = LocalDateTime.now().toLocalDate();
		long countToday = userInfoService.count(Wrappers.query(userInfo).between("create_time", LocalDateTime.of(localDate, LocalTime.MIN), LocalDateTime.of(localDate, LocalTime.MAX)));
		//昨日数量（admin-v3 R-1：昨日 00:00:00–23:59:59 create_time count，仅新增字段向后兼容）
		LocalDate yesterday = localDate.minusDays(1);
		long yesterdayCount = userInfoService.count(Wrappers.query(userInfo).between("create_time", LocalDateTime.of(yesterday, LocalTime.MIN), LocalDateTime.of(yesterday, LocalTime.MAX)));
		//本月新增数量（当月 1 号 00:00:00 至今日 23:59:59 的 create_time count，仅新增字段向后兼容）
		LocalDate firstDayOfMonth = localDate.withDayOfMonth(1);
		long countMonth = userInfoService.count(Wrappers.query(userInfo).between("create_time", LocalDateTime.of(firstDayOfMonth, LocalTime.MIN), LocalDateTime.of(localDate, LocalTime.MAX)));
		Map<String, Object> rs = new HashMap<>();
		rs.put("countTotal",countTotal);
		rs.put("countToday",countToday);
		rs.put("yesterdayCount",yesterdayCount);
		rs.put("countMonth",countMonth);
		return R.ok(rs);
	}

	/**
	 * 拉黑 / 恢复用户（设置 user_info.black_flag）。body: {id, blackFlag}，blackFlag '1' 拉黑，其余按 '0' 正常。
	 * 只更新 black_flag 单列，不触碰其他字段。登录/下单等拦截由上层读取本列实现（不在此处）。
	 * @param userInfo 商城用户（取 id 与 blackFlag）
	 * @return R
	 */
	@Operation(summary = "拉黑/恢复用户")
	@SysLog("拉黑/恢复用户")
	@PutMapping("/black")
	@PreAuthorize("@ato.hasAuthority('mall:userinfo:edit')")
	public R updateBlackFlag(@RequestBody UserInfo userInfo) {
		if (userInfo.getId() == null || userInfo.getId().trim().isEmpty()) {
			return R.failed("用户id不能为空");
		}
		String flag = "1".equals(userInfo.getBlackFlag()) ? "1" : "0";
		return R.ok(userInfoService.update(Wrappers.<UserInfo>lambdaUpdate()
				.eq(UserInfo::getId, userInfo.getId())
				.set(UserInfo::getBlackFlag, flag)));
	}

	/**
	 * 查询统计（根据某字段分组）
	 * @param userInfo
	 * @return
	 */
	@Operation(summary = "查询统计（根据某字段分组）")
	@GetMapping("/statistics/{column}")
	public R getStatisticsByColumn(@PathVariable("column") String column, UserInfo userInfo) {
		// 增加校验，仅允许字母、数字和下划线，防止非法字符或 SQL 注入
		if (!column.matches("^[a-zA-Z0-9_]+$")) {
			return R.failed("非法统计字段");
		}
		List<Map<String, Object>> appTypeMapList= userInfoService.listMaps(Wrappers.query(userInfo)
				.select(column + " as name, count(*) as value")
				.groupBy(column)
				.orderByAsc(column));
		return R.ok(appTypeMapList);
	}

	/**
	 * 批量查询用户简要信息
	 * @param ids 用户ID列表
	 * @return 用户简要信息列表
	 */
	@Operation(summary = "批量查询用户简要信息")
	// 只读查询，POST 只为承载 id 列表：不做操作审计（见 ArchExemptions.READ_ONLY_POST_METHODS）
	@PostMapping("/batch")
	public R batchGet(@RequestBody List<String> ids) {
		return R.ok(batchToSimpleVOs(ids));
	}

	/**
	 * 批量查询用户简要信息（服务间内部调用，不鉴权）。
	 *
	 * @param ids 用户ID列表
	 * @return 用户简要信息列表
	 */
	@Operation(summary = "批量查询用户简要信息（内部调用）")
	@PostMapping("/inside/batch")
	@Inside
	public R<List<UserInfoSimpleVO>> batchGetInside(@RequestBody List<String> ids) {
		return R.ok(batchToSimpleVOs(ids));
	}

	private List<UserInfoSimpleVO> batchToSimpleVOs(List<String> ids) {
		if (ids == null || ids.isEmpty()) {
			return Collections.emptyList();
		}
		List<UserInfo> users = userInfoService.listByIds(ids);
		return users.stream().map(this::toSimpleVO).collect(Collectors.toList());
	}

	/**
	 * 单个查询用户完整信息（服务间内部调用，不鉴权）。
	 * 跨服务返回脱敏 VO，避免直接序列化 UserInfo 实体里的 password / idCard / thirdSession。
	 */
	@Operation(summary = "查询用户完整信息（内部调用）")
	@GetMapping("/inside/get/{id}")
	@Inside
	public R<UserInfoFullVO> getInside(@PathVariable("id") String id) {
		UserInfo userInfo = userInfoService.getById(id);
		return R.ok(toFullVO(userInfo));
	}

	/**
	 * 按昵称/手机号模糊反查用户 ID（服务间内部调用，不鉴权）。
	 * 供 zhiflow-im 客服工作台的会话搜索使用：会话在 im 库、昵称/手机号在 mall 库，
	 * 跨库无法 JOIN，只能先把 keyword 换成 userId 集合再回 im 侧 IN 查询。
	 * <p>只返回 id，不返回任何用户明细，避免跨服务泄漏。
	 *
	 * @param limit IN 列表上限（服务端再夹一层 1..500），防止宽泛 keyword 打出巨大结果集
	 */
	@Operation(summary = "按昵称/手机号反查用户ID（内部调用）")
	@GetMapping("/inside/search-ids")
	@Inside
	public R<List<String>> searchIdsInside(@RequestParam("keyword") String keyword,
										   @RequestParam(value = "limit", defaultValue = "200") int limit) {
		if (keyword == null || keyword.isBlank()) {
			return R.ok(Collections.emptyList());
		}
		int size = Math.min(Math.max(limit, 1), 500);
		List<UserInfo> users = userInfoService.list(Wrappers.<UserInfo>lambdaQuery()
				.select(UserInfo::getId)
				.and(w -> w.like(UserInfo::getNickName, keyword).or().like(UserInfo::getPhone, keyword))
				.last("LIMIT " + size));
		return R.ok(users.stream().map(UserInfo::getId).collect(Collectors.toList()));
	}

	private UserInfoFullVO toFullVO(UserInfo u) {
		if (u == null) return null;
		UserInfoFullVO vo = new UserInfoFullVO();
		vo.setId(u.getId());
		vo.setNickName(u.getNickName());
		vo.setPhone(u.getPhone());
		vo.setHeadimgUrl(u.getHeadimgUrl());
		vo.setSex(u.getSex());
		vo.setCountry(u.getCountry());
		vo.setProvince(u.getProvince());
		vo.setCity(u.getCity());
		vo.setAddress(u.getAddress());
		vo.setAppType(u.getAppType());
		vo.setCreateTime(u.getCreateTime());
		vo.setUserCode(u.getUserCode());
		vo.setDeviceInfo(u.getDeviceInfo());
		vo.setDeviceType(u.getDeviceType());
		vo.setLastLoginTime(u.getLastLoginTime2());
		vo.setLastLoginIp(u.getLastLoginIp());
		vo.setRegisterIp(u.getRegisterIp());
		return vo;
	}

	/**
	 * 转换为简要信息 VO
	 */
	private UserInfoSimpleVO toSimpleVO(UserInfo userInfo) {
		UserInfoSimpleVO vo = new UserInfoSimpleVO();
		vo.setId(userInfo.getId());
		vo.setNickName(userInfo.getNickName());
		vo.setPhone(userInfo.getPhone());
		vo.setHeadimgUrl(userInfo.getHeadimgUrl());
		return vo;
	}
}
