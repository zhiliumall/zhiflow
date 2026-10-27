package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;

import java.util.Set;

/**
 * R5 支撑（S12 L4）：检出「吞掉异常并返回失败响应」的方法。
 *
 * <p><b>问题</b>：全局异常处理器只看得到抛到它面前的异常。业务代码里大量这种写法——
 * <pre>{@code
 * } catch (Exception e) {
 *     log.error("xxx 失败", e);
 *     return R.failed("xxx 失败");   // 异常不抛出
 * }
 * }</pre>
 * 异常永远进不了处理器，DB 无记录，只在容器日志里，重启即失。错误日志里
 * <b>长期看不到 C 端 URI</b>，而 mall-api 有 277 个 catch 块——就是这么来的。
 *
 * <p><b>判据的局限（刻意接受）</b>：ArchUnit 看的是字节码，拿不到「这个 return 在不在
 * catch 块里」这种源码结构信息。所以这里用可静态判定的近似——方法同时满足：
 * <ol>
 *   <li>调用了 {@code R.failed(...)}（返回失败响应）；</li>
 *   <li>调用了 {@code log.error/warn(...)} 且传了 Throwable（说明手里有异常对象）；</li>
 *   <li>没有调用 {@code ErrorLogRecorder.record(...)}。</li>
 * </ol>
 * 条件 2 是关键：只有把异常对象交给日志框架，才能确定这个方法确实捕获了异常而不是
 * 单纯做参数校验后返回失败。误报走豁免，不放宽判据。
 */
final class SwallowedExceptions {

	private static final String R_CLASS = "com.zhiflow.common.core.util.R";

	private static final String FAILED_METHOD = "failed";

	private static final String RECORDER_CLASS =
			"com.zhiflow.common.log.error.ErrorLogRecorder";

	private static final String RECORD_METHOD = "record";

	private static final Set<String> LOG_ERROR_METHODS = Set.of("error", "warn");

	private static final String SLF4J_LOGGER = "org.slf4j.Logger";

	private static final String THROWABLE = "java.lang.Throwable";

	private SwallowedExceptions() {
	}

	static boolean swallowsException(JavaMethod method) {
		boolean returnsFailure = false;
		boolean logsThrowable = false;
		boolean recordsError = false;

		for (JavaMethodCall call : method.getMethodCallsFromSelf()) {
			String owner = call.getTargetOwner().getName();
			String name = call.getTarget().getName();

			if (R_CLASS.equals(owner) && FAILED_METHOD.equals(name)) {
				returnsFailure = true;
			}
			else if (RECORDER_CLASS.equals(owner) && RECORD_METHOD.equals(name)) {
				recordsError = true;
			}
			else if (SLF4J_LOGGER.equals(owner) && LOG_ERROR_METHODS.contains(name)
					&& hasThrowableParameter(call)) {
				logsThrowable = true;
			}
		}
		return returnsFailure && logsThrowable && !recordsError;
	}

	/**
	 * 判断 {@code log.error(...)} 是否把 Throwable 传了进去——那是「手里有异常对象」的信号。
	 *
	 * <p><b>已知漏报</b>：只认 slf4j 的 {@code error(String, Throwable)} 两参重载。写成
	 * {@code log.error("失败 uri={}", uri, e)} 时编译器选的是 {@code error(String, Object...)}，
	 * 参数类型退化为 {@code Object[]}，从字节码看不出里面有异常。选择漏报而非把 {@code Object[]}
	 * 也算上：后者会让每个带占位符的 error 日志都进违例，噪音淹没真问题。
	 */
	private static boolean hasThrowableParameter(JavaMethodCall call) {
		return call.getTarget().getRawParameterTypes().stream()
				.map(JavaClass::getName)
				.anyMatch(THROWABLE::equals);
	}
}
