package com.zhiflow.api.weixin;

import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

/**
 * 微信应用服务 API 接口定义
 *
 * <p>[B7] 2026-09-14 契约治理：{@code getById} 原先返回持久化实体 {@code WxApp}，
 * 现在返回投影 {@link WxAppInsideDTO}（= 今天服务端真的返回的字段集合，
 * 即 {@code WxApp} 减去被显式置空的 {@code secret / token / aesKey} 与后台 CRUD/框架字段）。
 *
 */
@HttpExchange(url = "/weixin", accept = "application/json", contentType = "application/json")
public interface WxAppApi {

	/**
	 * 获取微信应用
	 */
	@GetExchange("/wxapp/inside/{id}")
	R<WxAppInsideDTO> getById(@PathVariable("id") String id,
					@RequestHeader(SecurityConstants.FROM) String from);
}
