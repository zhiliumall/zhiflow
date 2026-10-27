/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.constant.PortalConstants;
import com.zhiflow.common.portal.interceptor.ThirdSessionInterceptor;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.api.config.LoginPolicyProperties;
import com.zhiflow.mall.api.service.UserInfoService;
import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.portal.util.ApiUtil;
import com.zhiflow.mall.common.dto.UserInfoLoginResDTO;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.weixin.common.dto.LoginDTO;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import com.zhiflow.api.weixin.dto.WxMpUserInsideDTO;
import com.zhiflow.common.data.tenant.TenantAwareAsync;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletRequest;
import java.util.concurrent.TimeUnit;

/**
 * 微信用户
 *
 * @date 2019-08-25 15:39:39
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/wxuser")
@Tag(description = "wxuser", name = "微信用户API")
public class WxUserController {

	private final UserInfoService userInfoService;
	private final WxUserApi wxUserApi;
	private final RedisTemplate redisTemplate;

	/**
	 * 公众号仅获取openId（用于支付，不创建wxUser）
	 */
	@Operation(summary = "公众号仅获取openId（用于支付）")
	@PostMapping("/mp/code2openid")
	public R<String> code2OpenIdMp(HttpServletRequest request, @RequestBody LoginDTO loginDTO) {
		if (StrUtil.isBlank(loginDTO.getAppId())) {
			loginDTO.setAppId(ApiUtil.getAppId(request));
		}
		return wxUserApi.code2OpenIdMp(loginDTO, SecurityConstants.FROM_IN);
	}

	/**
	 * 公众号已授权用户登录
	 * @param loginDTO
	 * @return
	 */
	@Operation(summary = "公众号网页授权登录")
	@PostMapping("/loginmp")
	public R loginMp(HttpServletRequest request, @RequestBody LoginDTO loginDTO){
		if(StrUtil.isBlank(loginDTO.getJsCode())){
			return R.failed("jscode不能为空");
		}
		loginDTO.setAppId(ApiUtil.getAppId(request));
		return R.ok(userInfoService.loginMp(loginDTO, request));
	}

	/**
	 * 公众号绑定openId到当前登录用户
	 * 用于手机验证码登录后补充微信身份，使后续支付可走 JSAPI
	 */
	@Operation(summary = "公众号绑定openId到当前用户")
	@ApiLogin
	@PostMapping("/mp/bindOpenId")
	public R bindOpenIdMp(HttpServletRequest request, @RequestBody LoginDTO loginDTO) {
		ThirdSession thirdSession = ThirdSessionHolder.getThirdSession();
		if (thirdSession == null || StrUtil.isBlank(thirdSession.getUserId())) {
			return R.failed("用户未登录");
		}
		if (StrUtil.isBlank(loginDTO.getAppId())) {
			loginDTO.setAppId(ApiUtil.getAppId(request));
		}
		loginDTO.setMallUserId(thirdSession.getUserId());
		R<WxUserInsideDTO> result = wxUserApi.bindOpenIdMp(loginDTO, SecurityConstants.FROM_IN);
		if (!result.isOk() || result.getData() == null) {
			return R.failed(result.getMsg());
		}
		WxUserInsideDTO wxUser = result.getData();
		// 更新 ThirdSession 中的 openId 和 wxUserId，使后续支付可直接走 JSAPI
		thirdSession.setOpenId(wxUser.getOpenId());
		thirdSession.setWxUserId(wxUser.getId());
		thirdSession.setAppId(wxUser.getAppId());
		String thirdSessionHeader = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
		if (StrUtil.isNotBlank(thirdSessionHeader)) {
			String key = ThirdSessionInterceptor.getSessionRedisKey(thirdSessionHeader);
			redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(thirdSession),
				CacheConstants.TIME_OUT_SESSION, TimeUnit.HOURS);
		}
		return R.ok(wxUser);
	}
}
