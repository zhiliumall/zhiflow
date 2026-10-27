package com.zhiflow.common.core.observability;

/**
 * 自带三段式错误码的异常实现它，全局异常处理器就会把 {@link #errorCode()} 写进 {@code X-Error-Code}，
 * 而不是按异常类型套默认码（如 BizException 的 {@code BIZ-COMMON-001}）。
 *
 * <p>为什么是接口而不是在 BizException 上加字段：抛出方常在 common-security 看不到的模块里
 * （如 mall-common 的结算暂停异常），处理器只能认一个 common-core 里的类型；
 * 而 {@code BizException(errCode, msg)} 的 errCode 是给前端查多语言文案的，不能挪作他用。
 *
 * <p>返回值必须形如 {@code BIZ-AFFIL-090}（{@code ^(SYS|EXT|BIZ)-[A-Z0-9]+-\d{3}$}），不合规时处理器退回默认码。
 * 状态码不受影响——仍由异常类型决定。
 */
public interface ErrorCoded {

	String errorCode();

}
