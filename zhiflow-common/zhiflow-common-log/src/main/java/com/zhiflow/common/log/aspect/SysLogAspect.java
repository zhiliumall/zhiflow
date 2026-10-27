package com.zhiflow.common.log.aspect;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.log.config.BaseLogProperties;
import com.zhiflow.common.log.context.LogRecordContext;
import com.zhiflow.common.log.event.SysLogEvent;
import com.zhiflow.common.log.util.LogContentRenderer;
import com.zhiflow.common.log.util.SysLogUtils;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.springframework.context.ApplicationEventPublisher;

/**
 * 操作日志使用spring event异步入库
 *
 * @author
 */
@Slf4j
@Aspect
@AllArgsConstructor
public class SysLogAspect {

	private final ApplicationEventPublisher publisher;

	private final BaseLogProperties logProperties;

	/**
	 * 异常摘要最大长度。exception 列同时被 GlobalBizExceptionHandler 使用（那边截断到 2000），
	 * 这里保持一致，避免同一列出现两种长度口径。
	 */
	private static final int EXCEPTION_SUMMARY_MAX_LENGTH = 2000;

	@SneakyThrows
	@Around("@annotation(sysLog)")
	public Object around(ProceedingJoinPoint point, SysLog sysLog) {
		String strClassName = point.getTarget().getClass().getName();
		String strMethodName = point.getSignature().getName();
		log.debug("[类名]:{},[方法]:{}", strClassName, strMethodName);

		com.zhiflow.upms.common.dto.SysLogPayload logVo = collectQuietly(point, sysLog);
		logVo.setType(CommonConstants.LOG_TYPE_0);
		logVo.setTitle(sysLog.value());

		long startTime = System.currentTimeMillis();
		Object result = null;
		String errorMsg = null;
		try {
			result = point.proceed();
			return result;
		}
		catch (Throwable throwable) {
			// 失败也要留痕：改造前异常直接向上抛，下面的 publishEvent 永不执行，
			// 于是「谁试图做了什么但失败了」在 sys_log 里完全不存在。
			errorMsg = summarize(throwable);
			if (logProperties.isCaptureFailure()) {
				logVo.setType(CommonConstants.LOG_TYPE_9);
				logVo.setException(errorMsg);
			}
			// 原样重抛：审计改造不得改变业务错误语义
			throw throwable;
		}
		finally {
			logVo.setTime(System.currentTimeMillis() - startTime);
			// S4：渲染人话内容。不填 content 时返回 null，行为与改造前一致；
			// 渲染失败也返回 null（内部已降级记 WARN），绝不影响业务。
			logVo.setContent(LogContentRenderer.render(sysLog.content(), point, result, errorMsg));
			// ⚠️ ThreadLocal 必须无条件清理：线程复用会把本次请求的变量带给下一次。
			// 本项目在 ThirdSessionHolder 上踩过同款坑（生产实测污染人群 scope 判定）。
			LogRecordContext.clear();
			publishQuietly(logVo);
		}
	}

	/**
	 * 采集跑在 {@code point.proceed()} 之前，一抛业务就根本不执行 —— 审计不许挡业务。
	 *
	 * <p>2026-09-16 生产实测：gRPC 服务端经 {@code InternalCallContext} 调
	 * {@code SmsController.sendSmsInner}，内部请求的 URI 为 null，采集 NPE，
	 * 于是 upms-sms 走 gRPC 的每一次发送都失败（且 INTERNAL 不回落 REST），全站验证码发不出去。
	 * 采集失败时退化为只带标题的最小记录：业务照常执行，审计也不整条丢。
	 */
	private com.zhiflow.upms.common.dto.SysLogPayload collectQuietly(ProceedingJoinPoint point,
			SysLog sysLog) {
		try {
			return SysLogUtils.getSysLog(point, logProperties, sysLog.captureBody() && sysLog.captureRequestBody());
		}
		catch (RuntimeException e) {
			log.warn("审计日志采集失败，按最小记录继续 title={}", sysLog.value(), e);
			return new com.zhiflow.upms.common.dto.SysLogPayload();
		}
	}

	/**
	 * publishEvent 的异常绝不能掩盖业务异常——它发生在 finally 里，一旦抛出就会顶替
	 * 正在传播的业务异常，让调用方收到一个与故障无关的错误。
	 */
	private void publishQuietly(com.zhiflow.upms.common.dto.SysLogPayload logVo) {
		try {
			publisher.publishEvent(new SysLogEvent(logVo));
		}
		catch (Exception e) {
			log.error("发布审计日志事件失败 uri={} title={}", logVo.getRequestUri(), logVo.getTitle(), e);
		}
	}

	/**
	 * 异常摘要：类名 + message + 根因。不落完整堆栈——完整堆栈属错误日志（S12 的
	 * sys_error_log），审计表只需要「失败了，大致是什么错」，避免审计表被堆栈撑爆。
	 */
	private String summarize(Throwable throwable) {
		StringBuilder summary = new StringBuilder()
				.append(throwable.getClass().getName())
				.append(": ")
				.append(throwable.getMessage());

		Throwable rootCause = throwable;
		while (rootCause.getCause() != null && rootCause.getCause() != rootCause) {
			rootCause = rootCause.getCause();
		}
		if (rootCause != throwable) {
			summary.append(" | rootCause=")
					.append(rootCause.getClass().getName())
					.append(": ")
					.append(rootCause.getMessage());
		}

		String text = summary.toString();
		return text.length() > EXCEPTION_SUMMARY_MAX_LENGTH
				? text.substring(0, EXCEPTION_SUMMARY_MAX_LENGTH)
				: text;
	}

}
