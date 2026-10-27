package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.trade.CheckoutTokenService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 下单防重 token 发放。进结算页时取一枚，提交订单时放在请求头 {@code X-Checkout-Token}。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/checkout")
@Tag(description = "checkout", name = "结算页")
public class CheckoutTokenController {

	/** 提交订单时携带 token 的请求头。 */
	public static final String HEADER = "X-Checkout-Token";

	private final CheckoutTokenService checkoutTokenService;

	/**
	 * 下单接口共用：按认领 token。返回 null = 放行（{@code claimHolder[0]} 带回认领结论，调用方据此 complete / release）；
	 * 返回非 null = 直接把它回给前端（409 处理中 / 400 过期 / 200 原订单由调用方组装）。
	 */
	static String token(jakarta.servlet.http.HttpServletRequest request) {
		String t = request == null ? null : request.getHeader(HEADER);
		return t == null || t.isBlank() ? null : t.trim();
	}

	/** 设置当前响应的 HTTP 状态码（409 / 400），不改变返回体的 R 形状。 */
	static void status(int code) {
		var attrs = org.springframework.web.context.request.RequestContextHolder.getRequestAttributes();
		if (attrs instanceof org.springframework.web.context.request.ServletRequestAttributes sra && sra.getResponse() != null) {
			sra.getResponse().setStatus(code);
		}
	}

	@Operation(summary = "取下单防重 token（30 分钟有效）")
	@GetMapping("/token")
	@ApiLogin
	public R<Map<String, Object>> token() {
		String token = checkoutTokenService.issue(ThirdSessionHolder.getUserId());
		return R.ok(Map.of("token", token, "expiresIn", checkoutTokenService.issueTtlSeconds(), "header", HEADER));
	}
}
