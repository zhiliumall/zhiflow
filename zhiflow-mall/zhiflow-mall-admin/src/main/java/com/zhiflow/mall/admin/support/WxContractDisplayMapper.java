package com.zhiflow.mall.admin.support;

import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.weixin.common.entity.WxApp;
import com.zhiflow.weixin.common.entity.WxUser;

/**
 * [B7] 契约投影 → mall-admin 自己的展示实体。
 *
 * <p>weixin 契约在 2026-09-14 从「返回持久化实体」改成「返回投影 DTO」。
 * mall-admin 有两处把契约返回的整对象挂到自己的展示字段上再吐给后台前端：
 * {@code UserInfo.wxUser}（{@code @TableField(exist = false)}）与 {@code OrderInfo.app}。
 *
 * <p><b>刻意不去改 {@code UserInfo} / {@code OrderInfo} 这两个字段的类型</b>：
 * 它们住在 {@code zhiflow-mall-common} 里、被 mall-api 与 mall-admin 共用，
 * 改类型会让 {@code zhiflow-mall-common} 多出一条对 {@code zhiflow-api-weixin} 的依赖，
 * 而收益只是省掉下面这两个方法。这里映射回实体，后台前端拿到的 JSON <b>键集合不变</b>。
 *
 * <p>⚠️ 投影里没有的列（{@code createId / updateId / delFlag / createTime / updateTime}，以及 {@code WxApp} 的
 * {@code secret / token / aesKey}）在这两处展示上会变成 {@code null} —— 键还在，值没了。
 */
public final class WxContractDisplayMapper {

	private WxContractDisplayMapper() {
	}

	public static WxUser toDisplayEntity(WxUserInsideDTO src) {
		if (src == null) {
			return null;
		}
		WxUser out = new WxUser();
		out.setId(src.getId());
		out.setAppId(src.getAppId());
		out.setAppType(src.getAppType());
		out.setOpenId(src.getOpenId());
		out.setUnionId(src.getUnionId());
		out.setMallUserId(src.getMallUserId());
		out.setNickName(src.getNickName());
		out.setHeadimgUrl(src.getHeadimgUrl());
		out.setPhone(src.getPhone());
		out.setTenantId(src.getTenantId());
		out.setSex(src.getSex());
		// sessionKey 刻意不带过来：这条链路的终点是后台管理前端的 JSON，
		// 微信会话密钥没有任何理由出现在那里。契约上仍然有它（登录链路必需），
		// 只是不进"给人看"的这一份。
		return out;
	}

	public static WxApp toDisplayEntity(WxAppInsideDTO src) {
		if (src == null) {
			return null;
		}
		WxApp out = new WxApp();
		out.setId(src.getId());
		out.setAppType(src.getAppType());
		out.setName(src.getName());
		out.setLogo(src.getLogo());
		out.setRemarks(src.getRemarks());
		out.setTenantId(src.getTenantId());
		return out;
	}

}
