/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.lock.ratelimit.RateLimit;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.constant.PortalReturnCode;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.account.CaptchaGate;
import com.zhiflow.mall.api.account.SmsCodeService;
import com.zhiflow.mall.api.service.UserInfoService;
import com.zhiflow.mall.common.dto.ChangePasswordDTO;
import com.zhiflow.mall.common.dto.UserInfoLoginDTO;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 会员账号：登录、注册、资料、注销。
 *
 * <p>注册两条路：① 用户名或手机号 + 密码 + 滑块验证码（零依赖，默认可用）；② 手机号 + 短信验证码（配置了短信渠道才可用）。
 * 登录三条路：账号密码（账号 = 用户名或手机号）、手机号验证码（短信启用时）、微信网页授权（{@code /wxuser/loginmp}）。
 * 忘记密码不做自助找回：登录后在个人设置里改，或由后台重置。
 *
 * @date 2019-12-04 11:09:55
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/userinfo")
@Tag(description = "userinfo", name = "商城用户API")
public class UserInfoController {

	private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

	/** 用户名：字母开头，4–32 位字母数字下划线（字母开头保证不会和手机号混淆） */
	private static final Pattern USERNAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{3,31}$");

	/** 密码登录失败计数：错过一次之后，再登录必须带滑块验证码（软闸门，不封禁账号） */
	private static final String LOGIN_FAIL_PREFIX = "login_fail:";

	private static final Duration LOGIN_FAIL_WINDOW = Duration.ofMinutes(15);

	private final UserInfoService userInfoService;

	private final SmsCodeService smsCodeService;

	private final CaptchaGate captchaGate;

	private final StringRedisTemplate stringRedisTemplate;

	@Operation(summary = "当前用户")
	@GetMapping
	@ApiLogin
	public R getById() {
		return R.ok(userInfoService.getById(ThirdSessionHolder.getUserId()));
	}

	@Operation(summary = "账号密码登录（账号为用户名或手机号）")
	@RateLimit(name = "login", permits = 20, periodSeconds = 60, message = "登录太频繁，请稍后再试")
	@PostMapping("/login")
	public R login(HttpServletRequest request, @RequestBody UserInfoLoginDTO dto) {
		String account = StrUtil.trim(dto.getAccount());
		if (StrUtil.isBlank(account) || StrUtil.isBlank(dto.getPassword())) {
			return R.failed("请输入账号和密码");
		}
		String failKey = LOGIN_FAIL_PREFIX + account.toLowerCase();
		// 错过一次之后必须过滑块。这一步绝不计失败：否则不带验证码狂刷就能逼真实用户每次都转验证码
		if (stringRedisTemplate.hasKey(failKey) && !captchaGate.pass(dto.getGraphCode())) {
			return R.failed(Map.of("captchaRequired", true), PortalReturnCode.ERR_60012.getCode(),
					PortalReturnCode.ERR_60012.getMsg());
		}
		UserInfo userInfo = userInfoService.findByAccount(account);
		if (userInfo == null || StrUtil.isBlank(userInfo.getPassword())
				|| !ENCODER.matches(dto.getPassword(), userInfo.getPassword())) {
			// 账号不存在与密码错误同一个回复，不泄露账号是否存在
			stringRedisTemplate.opsForValue().increment(failKey);
			stringRedisTemplate.expire(failKey, LOGIN_FAIL_WINDOW);
			return R.failed(Map.of("captchaRequired", true), PortalReturnCode.ERR_60010.getCode(),
					PortalReturnCode.ERR_60010.getMsg());
		}
		stringRedisTemplate.delete(failKey);
		return R.ok(userInfoService.openSession(userInfo, request));
	}

	@Operation(summary = "手机号验证码登录（未注册的手机号自动注册）")
	@RateLimit(name = "login", permits = 20, periodSeconds = 60, message = "登录太频繁，请稍后再试")
	@PostMapping("/phone/login")
	public R loginByPhone(HttpServletRequest request, @RequestBody UserInfoLoginDTO dto) {
		if (!smsCodeService.enabled()) {
			return R.failed("未启用短信验证码，请使用账号密码登录");
		}
		if (!smsCodeService.verify(SmsCodeService.Scene.LOGIN, dto.getPhone(), dto.getCode())) {
			return R.failed(PortalReturnCode.ERR_60009.getCode(), PortalReturnCode.ERR_60009.getMsg());
		}
		UserInfo userInfo = userInfoService.findByPhone(dto.getPhone());
		if (userInfo == null) {
			userInfo = userInfoService.register(null, dto.getPhone(), null, request);
		}
		return R.ok(userInfoService.openSession(userInfo, request));
	}

	/**
	 * 注册。带 {@code code}（短信验证码）走短信注册，手机号必填、密码可不填；
	 * 否则走「用户名或手机号 + 密码 + 滑块验证码」。注册成功即登录。
	 */
	@Operation(summary = "注册")
	@RateLimit(name = "register", permits = 10, periodSeconds = 600, message = "注册太频繁，请稍后再试")
	@PostMapping("/register")
	public R register(HttpServletRequest request, @RequestBody UserInfoLoginDTO dto) {
		String username = StrUtil.trim(dto.getUsername());
		String phone = StrUtil.trim(dto.getPhone());
		if (StrUtil.isNotBlank(username) && !USERNAME.matcher(username).matches()) {
			return R.failed("用户名需字母开头，4–32 位字母、数字或下划线");
		}
		if (StrUtil.isNotBlank(phone) && !SmsCodeService.isPhone(phone)) {
			return R.failed("手机号格式不正确");
		}
		if (StrUtil.isNotBlank(dto.getPassword())) {
			String passwordError = checkPassword(dto.getPassword());
			if (passwordError != null) {
				return R.failed(passwordError);
			}
		}
		if (StrUtil.isNotBlank(dto.getCode())) {
			if (StrUtil.isBlank(phone) || !smsCodeService.verify(SmsCodeService.Scene.REGISTER, phone, dto.getCode())) {
				return R.failed(PortalReturnCode.ERR_60009.getCode(), PortalReturnCode.ERR_60009.getMsg());
			}
		}
		else {
			if (StrUtil.isBlank(username) && StrUtil.isBlank(phone)) {
				return R.failed("请填写用户名或手机号");
			}
			if (StrUtil.isBlank(dto.getPassword())) {
				return R.failed("请设置密码");
			}
			if (!captchaGate.pass(dto.getGraphCode())) {
				return R.failed(PortalReturnCode.ERR_60012.getCode(), PortalReturnCode.ERR_60012.getMsg());
			}
		}
		UserInfo userInfo = userInfoService.register(username, phone, dto.getPassword(), request);
		return R.ok(userInfoService.openSession(userInfo, request));
	}

	@Operation(summary = "退出登录")
	@PostMapping("/logout")
	public R logout(HttpServletRequest request) {
		userInfoService.logout(request);
		return R.ok();
	}

	/** 修改密码。短信注册的账号还没有密码时，不用填原密码，直接设置。 */
	@Operation(summary = "修改密码（已登录）")
	@PostMapping("/changePassword")
	@ApiLogin
	public R changePassword(@RequestBody ChangePasswordDTO dto) {
		String passwordError = checkPassword(dto.getNewPassword());
		if (passwordError != null) {
			return R.failed(passwordError);
		}
		UserInfo userInfo = userInfoService.getById(ThirdSessionHolder.getUserId());
		if (userInfo == null) {
			return R.failed("用户不存在");
		}
		if (StrUtil.isNotBlank(userInfo.getPassword())) {
			if (StrUtil.isBlank(dto.getOldPassword()) || !ENCODER.matches(dto.getOldPassword(), userInfo.getPassword())) {
				return R.failed("原密码不正确");
			}
			if (ENCODER.matches(dto.getNewPassword(), userInfo.getPassword())) {
				return R.failed("新密码不能与原密码相同");
			}
		}
		UserInfo patch = new UserInfo();
		patch.setId(userInfo.getId());
		patch.setPassword(ENCODER.encode(dto.getNewPassword()));
		userInfoService.updateById(patch);
		return R.ok();
	}

	/**
	 * 修改本人资料：只取白名单字段（昵称、头像、性别、地区），其余一律不改。
	 */
	@Operation(summary = "修改本人资料")
	@PutMapping
	@ApiLogin
	public R updateById(@RequestBody UserInfo userInfo) {
		return R.ok(userInfoService.updateById(toProfilePatch(ThirdSessionHolder.getUserId(), userInfo)));
	}

	/** 从请求体里只挑用户能自己改的资料字段（MyBatis-Plus 对 null 字段不更新）。 */
	static UserInfo toProfilePatch(String userId, UserInfo body) {
		UserInfo patch = new UserInfo();
		patch.setId(userId);
		if (body == null) {
			return patch;
		}
		patch.setNickName(body.getNickName());
		patch.setHeadimgUrl(body.getHeadimgUrl());
		patch.setSex(body.getSex());
		patch.setProvince(body.getProvince());
		patch.setCity(body.getCity());
		patch.setCountry(body.getCountry());
		patch.setAddress(body.getAddress());
		return patch;
	}

	@Operation(summary = "微信网页授权后同步昵称头像")
	@PutMapping("/mp")
	@ApiLogin
	public R updateByMp(@RequestBody WxOpenDataDTO wxOpenDataDTO) {
		ThirdSession thirdSession = ThirdSessionHolder.getThirdSession();
		if (thirdSession == null || StrUtil.isBlank(thirdSession.getWxUserId())) {
			return R.failed("当前不是微信授权登录");
		}
		wxOpenDataDTO.setAppId(thirdSession.getAppId());
		wxOpenDataDTO.setUserId(thirdSession.getWxUserId());
		return R.ok(userInfoService.updateByMp(wxOpenDataDTO));
	}

	/** 绑定 / 更换手机号（需短信验证码，短信未启用时不可用）。 */
	@Operation(summary = "绑定或更换手机号")
	@PostMapping("/phone")
	@ApiLogin
	public R updatePhone(@RequestBody UserInfoLoginDTO dto) {
		if (!smsCodeService.verify(SmsCodeService.Scene.BIND_PHONE, dto.getPhone(), dto.getCode())) {
			return R.failed(PortalReturnCode.ERR_60009.getCode(), PortalReturnCode.ERR_60009.getMsg());
		}
		String userId = ThirdSessionHolder.getUserId();
		UserInfo occupied = userInfoService.findByPhone(dto.getPhone());
		if (occupied != null && !occupied.getId().equals(userId)) {
			return R.failed("该手机号已被其他账号使用");
		}
		userInfoService.bindPhone(userId, dto.getPhone());
		return R.ok(userInfoService.getById(userId));
	}

	@Operation(summary = "注销账号")
	@DeleteMapping("/termination")
	@ApiLogin
	public R terminated(HttpServletRequest request) {
		userInfoService.terminate(request);
		return R.ok("注销账户成功");
	}

	private static String checkPassword(String password) {
		if (StrUtil.isBlank(password) || password.length() < 6 || password.length() > 32) {
			return "密码长度需为 6–32 位";
		}
		return null;
	}
}
