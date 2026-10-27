package com.zhiflow.common.security.handler;

import com.zhiflow.common.core.exception.BizBadRequestException;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.exception.TooManyRequestsException;
import com.zhiflow.common.core.observability.ErrorCode;
import com.zhiflow.common.core.observability.TraceIds;
import com.zhiflow.common.core.util.R;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.ConstraintViolationException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.SpringSecurityMessageSource;
import org.springframework.util.Assert;
import org.springframework.validation.BindException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.multipart.support.MissingServletRequestPartException;
import org.springframework.web.servlet.resource.NoResourceFoundException;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DatabindException;

import java.util.List;
import java.util.Locale;

/**
 * <p>
 * 全局异常处理器结合sentinel 全局异常处理器不能作用在 oauth server
 * </p>
 *
 * @author 辰
 * @date 2023年10月26日17:53:41
 */
@Slf4j
@Order(10000)
@RestControllerAdvice
@ConditionalOnExpression("!'${security.oauth2.client.clientId}'.isEmpty()")
public class GlobalBizExceptionHandler {

	/**
	 * 全局异常.
	 * @param e the e
	 * @return R
	 */
	@ExceptionHandler(Exception.class)
	@ResponseStatus(HttpStatus.INTERNAL_SERVER_ERROR)
	public R handleGlobalException(Exception e) {
		ErrorCode.markCurrentRequest(ErrorCode.codeOf(e, ErrorCode.systemCodeOf(e)));
		String errorNo = newErrorNo();
		log.error("全局异常信息 errorNo={} ex={}", errorNo, e.getMessage(), e);
		// 不回传 e.getLocalizedMessage()：未捕获异常的原文带包路径/实体类名/SQL 片段。
		// 业务异常（BizException / IllegalArgumentException 等）各有 handler，
		// 文案照旧原样返回，不受这里影响。排查用完整堆栈在日志里，按错误号可捞。
		return R.failed("服务异常，请稍后重试（错误号 %s）".formatted(errorNo));
	}

	/**
	 * 8 位短错误号：当前 trace id 的末 8 位（用户念得出来、按后缀能反查整条链路日志）；
	 * 没有 trace id 时退回随机 8 位 hex，与改造前同形。
	 */
	private static String newErrorNo() {
		return TraceIds.errorNo(TraceIds.currentTraceId());
	}

	/**
	 * 业务异常 → <b>HTTP 200</b> + {@code R(code=1, msg)}；<b>外部回调路径除外，仍回 500</b>。
	 *
	 * <p><b>为什么归位到 200</b>：业务拒绝（「销售奖励配置未开启」「余额不足」）是服务端给出的
	 * 确定性应答，不是系统故障。回 500 有三个实际代价：污染 5xx 监控口径；H5 的
	 * {@code isRetryableError} 把 HTTP 5xx 当瞬态故障重试，等于把一次业务拒绝放大成 N 次后端计数；
	 * 与同类的 {@link IllegalArgumentException}（200）不一致。
	 * 四端的通用请求封装都按响应体 {@code code} 判成败（管理后台前端
	 * {@code utils/http/index.ts:395} 的 {@code code === 1}、h5
	 * {@code createRequestClient/helpers.js} 的 default 分支、其它客户端 {@code api/api.js} 的
	 * {@code code != 0}），所以 200 与 500 对它们等价。
	 *
	 * <p><b>为什么回调路径要保持 500（R5 回调闸）</b>：渠道回调把 2xx 当 ACK 就会吃掉
	 * 15s~4h×10 次的重试自愈窗，失败模式是「钱已收、单不入账」的静默资金损失。
	 * 而回调链**确实能同步走到 BizException**——已证实的一条：
	 * {@code POST /mall/orderrefunds/notify-refunds/{tenantId}} → {@code doOrderRefundsResult()}
	 * → 秒杀已售数回补失败 {@code BizException("请重新提交")}，方法本身无 try-catch。
	 *
	 * <p>排除方式是<b>按请求路径</b>而不是按 Java 包：回调方法与普通业务方法同处一个 controller 类
	 * （{@code OrderInfoController} 里既有 {@code notify-order-wx} 也有用户端下单），按包排除做不到。
	 * 判据 {@link #isExternalCallbackRequest} 与 R5 回调闸自己用的 grep 正则同源，方向保守——
	 * 只会让端点维持现状，不可能把任何回调从非 2xx 变成 2xx。
	 *
	 * <p>副作用（已登记，可接受）：{@code /mallapi/distributionuser/notify-pref}、
	 * {@code /mall/promo/notify-template/**} 这类名字里带 notify 的普通业务端点也维持 500 现状。
	 *
	 * <p>需要「非 200 但不是回调」的场景用 {@link BizBadRequestException}（400），
	 * 典型是 {@code uni.uploadFile} 这类按 statusCode 判成败的端点。
	 */
	@ExceptionHandler(BizException.class)
	public ResponseEntity<R> handleGlobalException(BizException e, HttpServletRequest request) {
		// 自带错误码的子类（如结算暂停 BIZ-AFFIL-090，实现 ErrorCoded）用它自己的码
		ErrorCode.markCurrentRequest(ErrorCode.codeOf(e, ErrorCode.BIZ_COMMON));
		HttpStatus status = isExternalCallbackRequest(request)
				? HttpStatus.INTERNAL_SERVER_ERROR
				: HttpStatus.OK;
		if (status == HttpStatus.INTERNAL_SERVER_ERROR) {
			log.warn("回调路径业务异常，按 R5 保持非 2xx 以维持渠道重试窗 uri={} msg={}",
					request.getRequestURI(), e.getMessage());
		}
		return ResponseEntity.status(status).body(e.getCode() != null ? R.failed(e.getCode(), e.getMessage()) : R.failed(e.getMessage()));
	}

	/**
	 * 外部回调请求判据：URI 含 {@code notify} 或 {@code callback}（大小写不敏感）。
	 *
	 * <p>与 R5 回调闸的 {@code grep -rilE "notify|callback"} 同源。宁可多判、不可漏判：
	 * 多判的后果是某个普通端点维持今天的 500（零回归），漏判的后果是渠道停止重试（资金损失）。
	 */
	static boolean isExternalCallbackRequest(HttpServletRequest request) {
		if (request == null) {
			return false;
		}
		String uri = request.getRequestURI();
		if (uri == null) {
			return false;
		}
		String lower = uri.toLowerCase(Locale.ROOT);
		return lower.contains("notify") || lower.contains("callback");
	}

	/**
	 * 客户端入参非法 → <b>HTTP 400</b>。
	 *
	 * <p>专给「按 HTTP 状态码判成败」的端点用（{@code uni.uploadFile} 封装按
	 * {@code statusCode === 200} 判成功）。必须排在 {@link BizException} 分支之前生效——
	 * Spring 按异常类型的**具体程度**选 handler，子类分支自动优先，不依赖声明顺序。
	 *
	 * <p>只打本地日志：客户端行为不是服务端缺陷，属于客户端行为而非服务端缺陷。
	 */
	/**
	 * 触发限流 → <b>HTTP 429</b>。只打本地日志：是客户端行为，不是服务端缺陷。
	 */
	@ExceptionHandler(TooManyRequestsException.class)
	@ResponseStatus(HttpStatus.TOO_MANY_REQUESTS)
	public R handleTooManyRequestsException(TooManyRequestsException e) {
		log.info("触发限流: {}", e.getMessage());
		return R.failed(e.getMessage());
	}

	@ExceptionHandler(BizBadRequestException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleBizBadRequestException(BizBadRequestException e) {
		ErrorCode.markCurrentRequest(ErrorCode.codeOf(e, ErrorCode.BIZ_PARAM));
		log.warn("请求参数非法: {}", e.getMessage());
		return R.failed(e.getMessage());
	}

	/**
	 * 处理业务校验过程中碰到的非法参数异常 该异常基本由{@link Assert}抛出
	 * @see Assert#hasLength(String, String)
	 * @see Assert#hasText(String, String)
	 * @see Assert#isTrue(boolean, String)
	 * @see Assert#isNull(Object, String)
	 * @see Assert#notNull(Object, String)
	 * @param exception 参数校验异常
	 * @return API返回结果对象包装后的错误输出结果
	 */
	@ExceptionHandler(IllegalArgumentException.class)
	@ResponseStatus(HttpStatus.OK)
	public R handleIllegalArgumentException(IllegalArgumentException exception) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		String message = exception.getMessage();
		if (message != null && message.contains("Missing path variable")) {
			log.warn("路径变量缺失: {}", message);
			return R.failed("请求路径参数不完整，请检查接口调用");
		}
		log.error("非法参数,ex = {}", message, exception);
		return R.failed(message);
	}

	/**
	 * 权限拒绝 → HTTP 403。
	 *
	 * <p>此前这里 import 的是 {@code java.nio.file.AccessDeniedException}（文件系统的那个），
	 * Spring Security 的 {@code AuthorizationDeniedException} 压根进不来本分支，一路落到
	 * {@link #handleGlobalException(Exception)}——于是**没权限返回的是 500 而不是 403**，
	 * 并被当「全局异常」记进报错日志 59 条。改为 security 的
	 * {@link AccessDeniedException}（{@code AuthorizationDeniedException} 是其子类）。
	 *
	 * <p>只打本地日志：权限判定的正常产出，不是服务端缺陷。
	 */
	@ExceptionHandler(AccessDeniedException.class)
	@ResponseStatus(HttpStatus.FORBIDDEN)
	public R handleAccessDeniedException(AccessDeniedException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_AUTH_FORBIDDEN);
		String msg = SpringSecurityMessageSource.getAccessor()
			.getMessage("AbstractAccessDecisionManager.accessDenied", e.getMessage());
		log.warn("拒绝授权异常信息 ex={}", msg);
		return R.failed(e.getLocalizedMessage());
	}

	/**
	 * validation Exception
	 * @param exception
	 * @return R
	 */
	@ExceptionHandler({ MethodArgumentNotValidException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleBodyValidException(MethodArgumentNotValidException exception) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
		log.warn("参数绑定异常,ex = {}", fieldErrors.getFirst().getDefaultMessage());
		return R.failed("%s %s".formatted(fieldErrors.getFirst().getField(), fieldErrors.getFirst().getDefaultMessage()));
	}

	/**
	 * validation Exception (以form-data形式传参)
	 * @param exception
	 * @return R
	 */
	@ExceptionHandler({ BindException.class })
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R bindExceptionHandler(BindException exception) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		List<FieldError> fieldErrors = exception.getBindingResult().getFieldErrors();
		log.warn("参数绑定异常,ex = {}", fieldErrors.getFirst().getDefaultMessage());
		return R.failed(fieldErrors.getFirst().getDefaultMessage());
	}

	/**
	 * 缺少必需请求参数
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleMissingServletRequestParameter(MissingServletRequestParameterException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		log.warn("缺少请求参数: {}", e.getMessage());
		return R.failed("缺少必需参数: %s".formatted(e.getParameterName()));
	}

	/**
	 * 静态资源不存在 — 降级为 DEBUG，不污染 ERROR 日志
	 */
	@ExceptionHandler(NoResourceFoundException.class)
	@ResponseStatus(HttpStatus.NOT_FOUND)
	public R handleNoResourceFound(NoResourceFoundException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_NOT_FOUND);
		log.debug("静态资源不存在: {}", e.getResourcePath());
		return R.failed("资源不存在");
	}

	/**
	 * 请求方法不被支持 → <b>HTTP 405</b>。
	 *
	 * <p>此前全仓无该分支，一律落进 {@link #handleGlobalException(Exception)} 变成 <b>500</b>。
	 * 可复现的一组：{@code GET /mallapi/useraddress/list|default|location} —— 这三个 GET 映射
	 * 压根不存在，路径被 {@code DELETE /useraddress/{id}} 匹配上（把 list/default/location 当成
	 * {@code {id}}），方法不符抛本异常 → 500。三条实测均 {@code 500 / 111B / 服务异常（错误号 …）}。
	 *
	 * <p>不记 {@code sys_error_log}、不打 ERROR 堆栈：客户端调错方法不是服务端缺陷，
	 * 属于客户端行为而非服务端缺陷。响应体只回方法名，不回 {@code getSupportedMethods()}——
	 * 那等于免费告诉扫描器这条路径上还有哪些动词可用。
	 */
	@ExceptionHandler(HttpRequestMethodNotSupportedException.class)
	@ResponseStatus(HttpStatus.METHOD_NOT_ALLOWED)
	public R handleMethodNotSupported(HttpRequestMethodNotSupportedException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_METHOD);
		log.warn("请求方法不支持: method={}", e.getMethod());
		return R.failed("请求方法不支持: %s".formatted(e.getMethod()));
	}

	/**
	 * 参数类型不匹配 → <b>HTTP 400</b>（此前 500）。
	 *
	 * <p>可复现：{@code GET /mallapi/globalhotword/abc}（{@code @PathVariable("id") Long}）、
	 * {@code /goodsappraisestags/abc}、{@code /usergradeconfig/abc}，三条实测均 500。
	 *
	 * <p>只回**参数名**，不回 {@code getRequiredType()} 与 {@code getValue()}：
	 * 前者是实体/包装类名（信息泄露面，见 {@link #handleGlobalException(Exception)} 的 2026-07-31 教训），
	 * 后者是攻击者自己传的值、回显无意义。
	 *
	 * <p>注意与既有的 {@link IllegalArgumentException} 分支不冲突：本异常的继承链是
	 * {@code TypeMismatchException → PropertyAccessException → BeansException}，
	 * 而 {@code Page} 之类的模型绑定失败抛的是 {@code NumberFormatException}
	 * （{@code IllegalArgumentException} 子类），走的仍是老分支、仍回 200。
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleMethodArgumentTypeMismatch(MethodArgumentTypeMismatchException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		log.warn("参数类型不匹配: name={}", e.getName());
		return R.failed("参数格式不正确: %s".formatted(e.getName()));
	}

	/**
	 * 请求体无法读取（畸形 JSON / 类型不可反序列化）→ <b>HTTP 400</b>（此前 500）。
	 *
	 * <p>可复现：{@code POST /mallapi/userinfo/login} 送 {@code {"phone":} → 500。
	 *
	 * <p><b>严禁回传 {@code e.getMessage()}</b>：它带完整包路径与实体类名，例如
	 * {@code Cannot deserialize value of type `com.zhiflow.mall.common.entity.OrderInfo`}。
	 * 这正是一类内部信息泄露。只回通用文案 + <b>JSON 字段名</b>
	 * （字段名取自 {@link JsonMappingException#getPath()}，是报文里的键名，不含任何类型信息）。
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleHttpMessageNotReadable(HttpMessageNotReadableException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		String field = firstJsonFieldOf(e);
		log.warn("请求体解析失败: field={} ex={}", field, e.getMessage());
		return field == null
				? R.failed("请求体格式错误，请检查报文")
				: R.failed("请求体格式错误: %s".formatted(field));
	}

	/**
	 * 从 Jackson 的映射路径里取第一个字段名。取不到返回 {@code null}。
	 *
	 * <p>只取 {@code getFieldName()} / {@code getPropertyName()}（报文里的键名），刻意不碰 {@code getFrom()}——
	 * 那个是持有该字段的 Java 对象，{@code toString()} 会带类名。Jackson 2 与 3 两套异常都认（Boot 4 起并存）。
	 */
	private static String firstJsonFieldOf(HttpMessageNotReadableException e) {
		Throwable cause = e.getCause();
		// Jackson 2（本仓 HTTP 主路径：preferred-json-mapper=jackson2）
		if (cause instanceof DatabindException jme) {
			return jme.getPath().stream()
					.map(DatabindException.Reference::getPropertyName)
					.filter(name -> name != null && !name.isBlank())
					.findFirst()
					.orElse(null);
		}
		// Jackson 3（Boot 4 起 classpath 上并存；standalone MockMvc / 未走 Boot 自动配置的转换器会用它）
		if (cause instanceof DatabindException dbe) {
			return dbe.getPath().stream()
					.map(JacksonException.Reference::getPropertyName)
					.filter(name -> name != null && !name.isBlank())
					.findFirst()
					.orElse(null);
		}
		return null;
	}

	/**
	 * 方法级参数约束校验失败（{@code @Validated} + {@code @NotBlank} 等直接标在
	 * {@code @RequestParam}/{@code @PathVariable} 上）→ <b>HTTP 400</b>。
	 *
	 * <p>与既有的 {@link MethodArgumentNotValidException}（{@code @RequestBody @Valid}）、
	 * {@link BindException}（form-data 绑定）互补，三者覆盖 Bean Validation 的三种触发形态。
	 *
	 * <p>{@code jakarta.validation} 由 {@code zhiflow-common-core} 的
	 * {@code spring-boot-starter-validation}（compile 作用域）带进来，所有依赖方运行期必然有该类——
	 * 这一条必须先验证：advice 是全部服务共用的，某个服务缺类等于该服务整个上下文起不来，
	 * 而单测直接 new 永远测不到。
	 *
	 * <p>只回 {@code getPropertyPath()} 末段（参数名）与校验文案，不回 {@code getInvalidValue()}。
	 */
	@ExceptionHandler(ConstraintViolationException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleConstraintViolation(ConstraintViolationException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		String detail = e.getConstraintViolations() == null ? null
				: e.getConstraintViolations().stream()
					.findFirst()
					.map(v -> {
						String path = v.getPropertyPath() == null ? "" : v.getPropertyPath().toString();
						String leaf = path.contains(".") ? path.substring(path.lastIndexOf('.') + 1) : path;
						return leaf.isBlank() ? v.getMessage() : "%s %s".formatted(leaf, v.getMessage());
					})
					.orElse(null);
		log.warn("参数约束校验失败: {}", detail);
		return R.failed(detail == null ? "参数校验不通过" : detail);
	}

	/**
	 * multipart 请求缺少必需的文件部分 → <b>HTTP 400</b>（此前 500）。
	 *
	 * <p>{@code POST /mallapi/file/upload} 不带 {@code file} 就是这个。
	 * （该端点 2026-08-17 起需要登录，匿名请求在这之前就被
	 * {@code @ApiLogin} 拦成 {@code 200 {"code":60002}}，所以这条分支只对登录态可见。）
	 */
	@ExceptionHandler(MissingServletRequestPartException.class)
	@ResponseStatus(HttpStatus.BAD_REQUEST)
	public R handleMissingServletRequestPart(MissingServletRequestPartException e) {
		ErrorCode.markCurrentRequest(ErrorCode.BIZ_PARAM);
		log.warn("缺少上传文件: {}", e.getRequestPartName());
		return R.failed("缺少必需的文件参数: %s".formatted(e.getRequestPartName()));
	}
}
