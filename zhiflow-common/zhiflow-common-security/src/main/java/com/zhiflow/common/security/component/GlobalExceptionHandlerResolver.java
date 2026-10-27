package com.zhiflow.common.security.component;

import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.core.util.R;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.List;

/**
 * @author 辰
 * @date 2023年10月26日17:09:15
 * 全局异常处理器
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandlerResolver {

	/**
	 * 全局异常.
	 *
	 * @param e the e
	 * @return R
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public R handleGlobalException(Exception e) {
		// 与 GlobalBizExceptionHandler 同口径：未捕获异常不回传原文（含包路径/实体类名/SQL 片段），
		// 只给通用文案 + 8 位错误号，完整堆栈进日志。这个 advice 没有 @Order，
		// 优先级低于 GlobalBizExceptionHandler(@Order(10000))，但两处都得堵，否则条件不满足时照旧泄露。
		ErrorCode.markCurrentRequest(ErrorCode.codeOf(e, ErrorCode.systemCodeOf(e)));
		String errorNo = TraceIds.errorNo(TraceIds.currentTraceId());
		log.error("全局异常信息 errorNo={} ex={}", errorNo, e.getMessage(), e);
		return R.failed("服务异常，请稍后重试（错误号 %s）".formatted(errorNo));
	}

	/**
	 * AccessDeniedException
	 *
	 * @param e the e
	 * @return R
	 */
	@ExceptionHandler(AccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public R handleAccessDeniedException(AccessDeniedException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_AUTH_FORBIDDEN);
		String msg = SpringSecurityMessageSource.getAccessor()
			.getMessage("AbstractAccessDecisionManager.accessDenied"
				, e.getMessage());
		log.error("拒绝授权异常信息 ex={}", msg, e);
		return R.failed(e.getLocalizedMessage());
	}

	/**
	 * validation Exception
	 *
	 * @param exception
	 * @return R
	 */
	@ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleBodyValidException(MethodArgumentNotValidException exception) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
		log.error("参数绑定异常,ex = {}", fieldErrors.getFirst().getDefaultMessage());
		return R.failed(fieldErrors.getFirst().getDefaultMessage());
	}
}
