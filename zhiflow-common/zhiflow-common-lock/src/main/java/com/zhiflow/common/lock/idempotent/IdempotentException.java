package com.zhiflow.common.lock.idempotent;

/**
 * 幂等拦截异常。
 *
 * <p>继承 {@code RuntimeException} 以便被全局异常处理器统一转成业务失败响应——
 * 这不是系统错误，是「你重复提交了」，对用户应当表现为一条可读提示。
 */
public class IdempotentException extends RuntimeException {

	public IdempotentException(String message) {
		super(message);
	}
}
