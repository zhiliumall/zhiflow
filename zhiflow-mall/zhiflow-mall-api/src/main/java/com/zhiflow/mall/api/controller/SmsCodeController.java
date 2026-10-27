package com.zhiflow.mall.api.controller;

import com.zhiflow.common.core.util.ClientIpResolver;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.common.portal.constant.PortalReturnCode;
import com.zhiflow.mall.api.account.CaptchaGate;
import com.zhiflow.mall.api.account.SmsCodeService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/**
 * 短信验证码。短信渠道未配置时 {@code /sms/status} 回 enabled=false，前端隐藏手机号验证码登录与短信注册入口。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/sms")
@Tag(name = "短信验证码")
public class SmsCodeController {

	private final SmsCodeService smsCodeService;

	private final CaptchaGate captchaGate;

	@Operation(summary = "短信是否启用")
	@GetMapping("/status")
	public R<Map<String, Boolean>> status() {
		return R.ok(Map.of("enabled", smsCodeService.enabled()));
	}

	/**
	 * 发送验证码。必须先过滑块验证码（{@code graphCode} 为二次校验 token）。
	 */
	@Operation(summary = "发送短信验证码")
	@ApiLogin(mustLogin = false)
	@PostMapping("/code")
	public R<Void> send(HttpServletRequest request, @RequestBody SendCodeRequest body) {
		if (!captchaGate.pass(body.getGraphCode())) {
			return R.failed(PortalReturnCode.ERR_60012.getCode(), PortalReturnCode.ERR_60012.getMsg());
		}
		SmsCodeService.Scene scene;
		try {
			scene = SmsCodeService.Scene.valueOf(String.valueOf(body.getScene()).toUpperCase());
		}
		catch (IllegalArgumentException e) {
			return R.failed("验证码用途不正确");
		}
		smsCodeService.send(scene, body.getPhone(), ClientIpResolver.trusted(request));
		return R.ok();
	}

	@Data
	public static class SendCodeRequest {

		private String phone;

		/** LOGIN / REGISTER / BIND_PHONE */
		private String scene;

		/** 滑块验证码二次校验 token */
		private String graphCode;
	}
}
