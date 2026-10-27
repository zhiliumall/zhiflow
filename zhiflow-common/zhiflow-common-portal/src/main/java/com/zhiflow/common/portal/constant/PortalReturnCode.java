/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.portal.constant;

/**
 * 全局返回码
 * 2019年7月25日
 */
public enum PortalReturnCode {

	ERR_60000(60000, "系统错误，请稍候再试"){},//其它错误
	ERR_60001(60001, "登录超时，请重新登录"){},
	ERR_60002(60002, "session不能为空"){},
	ERR_60003(60003, "请先登录商城"){},
	ERR_60004(60004, "租户ID不能为空"){},
	ERR_60006(60006, "该手机号已注册，请直接登录"){},
	ERR_60007(60007, "该手机号未注册"){},
	ERR_60008(60008, "验证码发送过频繁，请稍后再试"){},
	ERR_60009(60009, "验证码不正确"){},
	ERR_60010(60010, "账号或密码错误"){},
	/**
	 * 占位保留，当前无任何代码返回该码。
	 *
	 * <p>历史坑：其它客户端登录页写死认 60011 为「账号锁定」并弹全屏 15 分钟倒计时卡
	 * （handleRiskLockError + data.lockRemainSeconds），但后端枚举里从来没有 60011，
	 * 该锁定卡自上线起从未展示过。2026-08-09 密码登录改为验证码闸门后已无「锁定」语义，
	 * 此处补齐枚举纯粹是占坑：防止后续有人给 60011 赋新语义，导致老客户端弹出假锁定卡。
	 */
	ERR_60011(60011, "密码错误次数过多，请稍后再试"){},
	/**
	 * 需要通过图形验证码（tianai 二次校验）后才能继续。
	 * 密码登录失败过至少一次的手机号，后续每次登录都必须携带有效 graphCode。
	 */
	ERR_60012(60012, "请完成安全验证"){},
	;

	PortalReturnCode(int code, String msg) {
		this.code = code;
		this.msg = msg;
	}

	private int code;
	private String msg;

	public int getCode() {
		return code;
	}
	public void setCode(int code) {
		this.code = code;
	}
	public String getMsg() {
		return msg;
	}
	public void setMsg(String msg) {
		this.msg = msg;
	}

	@Override
	public String toString() {
		return "MyReturnCode{" + "code='" + code + '\'' + "msg='" + msg + '\'' + '}';
	}

}
