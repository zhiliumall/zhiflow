/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.weixin.common.dto.LoginDTO;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import jakarta.servlet.http.HttpServletRequest;

/**
 * 商城用户（会员账号）。
 *
 * <p>登录方式：账号密码（账号 = 用户名或手机号）、手机号验证码（短信启用时）、微信网页授权（微信内）。
 * 登录成功返回的 {@link UserInfo#getThirdSession()} 是会话令牌，前端放在请求头 third-session 里。
 *
 * @date 2019-12-04 11:09:55
 */
public interface UserInfoService extends IService<UserInfo> {

	/** 微信网页授权登录：用 code 换微信用户，没有绑定商城用户时建一个（无手机号）。 */
	UserInfo loginMp(LoginDTO loginDTO, HttpServletRequest request);

	/** 微信网页授权后同步昵称、头像。 */
	UserInfo updateByMp(WxOpenDataDTO wxOpenDataDTO);

	/** 按账号（用户名或手机号）查未删除的用户，查不到返回 null。 */
	UserInfo findByAccount(String account);

	/** 按手机号查未删除的用户，查不到返回 null。 */
	UserInfo findByPhone(String phone);

	/**
	 * 注册新会员（密码已由调用方校验格式，这里加密落库）。用户名、手机号至少给一个，且都不能已被占用。
	 *
	 * @param password 明文密码；为空表示「验证码注册」，账号暂无密码（登录后可在个人设置里设）
	 */
	UserInfo register(String username, String phone, String password, HttpServletRequest request);

	/** 为已通过身份校验的用户开会话：微信内且已有微信身份时复用那条会话并绑定，否则新建。 */
	UserInfo openSession(UserInfo userInfo, HttpServletRequest request);

	/** 退出登录：删掉当前会话（微信内会话只摘掉商城用户，保留微信身份）。 */
	void logout(HttpServletRequest request);

	/** 注销账号：清空手机号、用户名，昵称改「已注销用户」，软删；解绑微信用户。 */
	void terminate(HttpServletRequest request);

	/** 绑定 / 更换手机号（调用方已校验短信验证码与占用情况）。 */
	void bindPhone(String userId, String phone);
}
