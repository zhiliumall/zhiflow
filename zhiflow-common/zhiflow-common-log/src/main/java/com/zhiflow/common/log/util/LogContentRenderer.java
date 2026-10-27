package com.zhiflow.common.log.util;

import com.zhiflow.common.log.context.LogRecordContext;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.Expression;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 操作日志内容模板渲染（S4）。
 *
 * <p>把 {@code @SysLog(content = "...")} 上的 SpEL 模板渲染成人话，
 * 让 {@code sys_log} 从「删除电子券 + 一个 id」变成
 * 「删除优惠券模板「双十一满减」，连带作废已发放 1910 张」。
 *
 * <h2>可用变量</h2>
 * <ul>
 *   <li>方法入参，按参数名，如 {@code #id}</li>
 *   <li>{@link LogRecordContext} 里业务塞的对象，如 {@code #coupon}、{@code #impact}
 *       —— 这是 {@code DELETE /{id}} 这类只有 id 的接口唯一能拿到对象名称的途径</li>
 *   <li>{@code #_ret}：方法返回值（失败时为 null）</li>
 *   <li>{@code #_errorMsg}：失败时的异常摘要（成功时为 null）</li>
 * </ul>
 *
 * <h2>失败一律降级</h2>
 * 模板是字符串，编译器不管，写错是必然会发生的。渲染失败只记 WARN 并返回 null，
 * 调用方保持原有的静态 title —— <b>一次日志渲染绝不能让业务操作失败</b>。
 */
@Slf4j
public final class LogContentRenderer {

	private static final ExpressionParser PARSER = new SpelExpressionParser();

	private static final ParameterNameDiscoverer PARAM_NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

	/** 模板编译结果缓存：同一个注解的模板每次请求都要用，重复解析纯属浪费 */
	private static final Map<String, Expression> CACHE = new ConcurrentHashMap<>(64);

	/** 渲染结果上限，与 sys_log.content 列宽对齐，防止模板拼出超长文本撑爆表 */
	private static final int MAX_LENGTH = 1000;

	private LogContentRenderer() {
	}

	/**
	 * 渲染模板。
	 *
	 * @param template 注解上的 SpEL 模板；空白直接返回 null（表示未启用）
	 * @param point    切点，用于取方法入参
	 * @param result   方法返回值，失败时传 null
	 * @param errorMsg 异常摘要，成功时传 null
	 * @return 渲染后的人话；未启用或渲染失败时返回 null
	 */
	public static String render(String template, ProceedingJoinPoint point, Object result, String errorMsg) {
		if (template == null || template.isBlank()) {
			return null;
		}
		try {
			StandardEvaluationContext context = new StandardEvaluationContext();

			// 1. 方法入参（按参数名）
			MethodSignature signature = (MethodSignature) point.getSignature();
			Method method = signature.getMethod();
			String[] names = PARAM_NAME_DISCOVERER.getParameterNames(method);
			Object[] args = point.getArgs();
			if (names != null) {
				for (int i = 0; i < names.length && i < args.length; i++) {
					context.setVariable(names[i], args[i]);
				}
			}

			// 2. 业务塞进 LogRecordContext 的对象（后放，允许覆盖同名入参）
			LogRecordContext.getAll().forEach(context::setVariable);

			// 3. 约定变量，对齐 yudao
			context.setVariable("_ret", result);
			context.setVariable("_errorMsg", errorMsg);

			Object value = CACHE.computeIfAbsent(template, PARSER::parseExpression).getValue(context);
			if (value == null) {
				return null;
			}
			String text = String.valueOf(value);
			return text.length() > MAX_LENGTH ? text.substring(0, MAX_LENGTH) : text;
		}
		catch (Exception e) {
			// 降级：模板写错（字段名拼错、空指针…）不得影响业务，调用方会保持静态 title
			log.warn("操作日志内容渲染失败，降级为静态 title。template={}", template, e);
			return null;
		}
	}
}
