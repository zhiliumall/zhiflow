package com.zhiflow.common.core.exception;

/**
 * 触发限流（{@code @RateLimit}）—— 由全局异常处理器返回 <b>HTTP 429</b> + {@code R(code=1, msg)}。
 *
 * <p>继承 {@link BizException}：现存的 {@code catch (BizException e)} 站点全部继续命中。
 */
public class TooManyRequestsException extends BizException {

	public TooManyRequestsException(String message) {
		super(message);
	}
}
