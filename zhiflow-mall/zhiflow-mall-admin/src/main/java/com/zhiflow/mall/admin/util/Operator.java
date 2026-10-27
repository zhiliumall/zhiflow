package com.zhiflow.mall.admin.util;

import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;

/**
 * 当前后台操作人（id 为 sys_user.id）。拿不到登录态（定时任务、单测）时两项为 null，调用方按「未知操作人」处理。
 */
public record Operator(String id, String name) {

	public static Operator current() {
		try {
			BaseUser user = SecurityUtils.getUser();
			return user == null ? new Operator(null, null) : new Operator(user.getId(), user.getName());
		} catch (Exception e) {
			return new Operator(null, null);
		}
	}
}
