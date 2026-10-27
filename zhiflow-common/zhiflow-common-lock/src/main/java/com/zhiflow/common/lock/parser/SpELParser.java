package com.zhiflow.common.lock.parser;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.context.expression.MethodBasedEvaluationContext;
import org.springframework.core.DefaultParameterNameDiscoverer;
import org.springframework.core.ParameterNameDiscoverer;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;

import java.lang.reflect.Method;

/**
 * SpEL 表达式解析器
 *
 * @date 2025-12-22
 */
public class SpELParser {

	private static final ExpressionParser PARSER = new SpelExpressionParser();

	private static final ParameterNameDiscoverer NAME_DISCOVERER = new DefaultParameterNameDiscoverer();

	/**
	 * 解析 SpEL 表达式
	 *
	 * @param expression 表达式（如 #userId, #entity.getId()）
	 * @param joinPoint  切入点
	 * @return 解析后的值
	 */
	public static String parseKey(String expression, ProceedingJoinPoint joinPoint) {
		Method method = ((MethodSignature) joinPoint.getSignature()).getMethod();
		Object[] args = joinPoint.getArgs();

		StandardEvaluationContext context = new MethodBasedEvaluationContext(joinPoint.getTarget(), method, args,
				NAME_DISCOVERER);

		Object value = PARSER.parseExpression(expression).getValue(context);
		return value != null ? value.toString() : "";
	}

	/**
	 * 解析多个键并拼接
	 *
	 * @param expressions 表达式数组
	 * @param joinPoint   切入点
	 * @param separator   分隔符
	 * @return 拼接后的键
	 */
	public static String parseKeys(String[] expressions, ProceedingJoinPoint joinPoint, String separator) {
		if (expressions == null || expressions.length == 0) {
			return "";
		}

		StringBuilder keyBuilder = new StringBuilder();
		for (int i = 0; i < expressions.length; i++) {
			String key = parseKey(expressions[i], joinPoint);
			keyBuilder.append(key);
			if (i < expressions.length - 1) {
				keyBuilder.append(separator);
			}
		}
		return keyBuilder.toString();
	}

}
