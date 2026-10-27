package com.zhiflow.common.log.util;

import cn.hutool.core.util.StrUtil;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;
import com.zhiflow.common.core.jackson.JavaTimeModule;
import com.zhiflow.common.log.config.BaseLogProperties;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.io.OutputStream;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 审计日志入参采集：合并 query 参数、{@code @PathVariable}、{@code @RequestBody} 三源。
 *
 * <p>改造前 {@code SysLogUtils} 只取 {@code request.getParameterMap()}，于是
 * 「DELETE + 路径变量」和所有 {@code @RequestBody} 接口的 params 都是空的——而删除类接口
 * 恰恰是审计重点，日志里只有一个 URI 说明不了任何事。
 *
 * <p><b>为什么不用 {@code ContentCachingRequestWrapper}</b>：方案建议的是全局 Filter，
 * 会对所有请求缓存 body。本项目有商品图/附件上传，全局缓存 body 的内存与 multipart 风险大。
 * 从切面 {@code point.getArgs()} 直接取已绑定好的参数对象，等效且没有全局副作用。
 *
 * <p>输出 JSON 而非 query-string：S4 要求往 params 里并入结构化的 {@code dangerConfirm}
 * 字段，字符串拼接撑不住。
 */
@Slf4j
public final class SysLogParamsCollector {

	/**
	 * 必须注册 {@link JavaTimeModule}：裸 {@code ObjectMapper} 序列化 {@code LocalDateTime}
	 * 会抛「Java 8 date/time type not supported by default」，而实体入参（{@code PageDevise}
	 * 的 createTime/updateTime、订单/商品的时间字段等）几乎都带 {@code LocalDateTime}——
	 * 异常被 {@link #collect} 的 catch 兜住后整个 body 退化成 {@code _collectError}，
	 * 于是「改了什么」在审计日志里全丢。2026-07-30 装修被覆盖一事就是这样查不到载荷的。
	 */
	private static final ObjectMapper MAPPER = JsonMapper.builder()
            .addModule(new JavaTimeModule())
            // ⚠️ 必须显式打开：本类**依赖序列化失败抛异常**来落 _bodyError 诊断标记（见下面的 catch）。
            // Jackson 2 的 FAIL_ON_EMPTY_BEANS 默认就是开启，所以原来不用写；
            // Jackson 3 把默认值改成了关闭，不写的话序列化不了的对象会静默变成 {}，
            // 审计日志里既没有载荷也没有错误标记 —— 这正是 2026-07-30 装修被覆盖那次查不到载荷的形状。
            .enable(SerializationFeature.FAIL_ON_EMPTY_BEANS)
            .build();

	private static final String MASKED = "***";

	private static final String TRUNCATED_SUFFIX = "...(truncated)";

	/** 不可/不宜序列化的参数类型：容器对象与流，序列化会炸或产生巨量无用内容 */
	private static final List<Class<?>> SKIPPED_PARAM_TYPES = List.of(
			ServletRequest.class, ServletResponse.class, MultipartFile.class,
			InputStream.class, OutputStream.class);

	private SysLogParamsCollector() {
	}

	/**
	 * @param point   切点，用于取方法签名与实参
	 * @param request 当前请求，用于取 query 参数
	 * @return 合并后的 JSON 字符串，已掩码并按 maxLength 截断
	 */
	public static String collect(ProceedingJoinPoint point, HttpServletRequest request,
			BaseLogProperties properties) {
		return collect(point, request, properties, true);
	}

	/** Per-operation body opt-out for sensitive commands; query/path remain auditable. */
	public static String collect(ProceedingJoinPoint point, HttpServletRequest request,
			BaseLogProperties properties, boolean captureBody) {
		ObjectNode root = MAPPER.createObjectNode();
		Set<String> excluded = excludedFields(properties);

		try {
			putQueryParams(root, request, excluded);
			if (captureBody && properties.isCaptureBody()) {
				putMethodArgs(root, point, excluded);
			}
		}
		catch (Exception e) {
			// 采集失败绝不能影响业务请求，也不能让审计整条丢失——降级为标记
			log.warn("审计入参采集失败 uri={}: {}", request.getRequestURI(), e.getMessage());
			root.put("_collectError", String.valueOf(e.getMessage()));
		}

		return truncate(root.toString(), properties.getMaxLength());
	}

	private static void putQueryParams(ObjectNode root, HttpServletRequest request, Set<String> excluded) {
		Map<String, String[]> parameterMap = request.getParameterMap();
		if (parameterMap.isEmpty()) {
			return;
		}
		ObjectNode query = root.putObject("query");
		parameterMap.forEach((name, values) -> {
			if (isExcluded(name, excluded)) {
				query.put(name, MASKED);
			}
			else if (values.length == 1) {
				query.put(name, values[0]);
			}
			else {
				ArrayNode array = query.putArray(name);
				for (String value : values) {
					array.add(value);
				}
			}
		});
	}

	private static void putMethodArgs(ObjectNode root, ProceedingJoinPoint point, Set<String> excluded) {
		MethodSignature signature = (MethodSignature) point.getSignature();
		Method method = signature.getMethod();
		Annotation[][] parameterAnnotations = method.getParameterAnnotations();
		Class<?>[] parameterTypes = method.getParameterTypes();
		String[] parameterNames = signature.getParameterNames();
		Object[] args = point.getArgs();

		ObjectNode path = null;

		for (int i = 0; i < args.length && i < parameterAnnotations.length; i++) {
			Object arg = args[i];
			if (arg == null || isSkippedType(parameterTypes[i])) {
				continue;
			}

			PathVariable pathVariable = findAnnotation(parameterAnnotations[i], PathVariable.class);
			if (pathVariable != null) {
				if (path == null) {
					path = root.putObject("path");
				}
				String name = StrUtil.isNotBlank(pathVariable.value()) ? pathVariable.value()
						: parameterName(parameterNames, i);
				path.put(name, isExcluded(name, excluded) ? MASKED : String.valueOf(arg));
				continue;
			}

			if (findAnnotation(parameterAnnotations[i], RequestBody.class) != null) {
				// body 单独兜异常：某个入参序列化失败不该连坐掉已采到的 query/path。
				// 旧实现只在最外层兜，一个 LocalDateTime 字段就能让整条审计只剩 _collectError。
				try {
					root.set("body", mask(MAPPER.valueToTree(arg), excluded));
				}
				catch (Exception e) {
					log.warn("审计 body 序列化失败 type={}: {}", arg.getClass().getName(), e.getMessage());
					root.put("_bodyError", arg.getClass().getSimpleName() + ": " + e.getMessage());
				}
			}
		}
	}

	/**
	 * 递归掩码。
	 *
	 * <p>刻意走 JSON 树而不是 Jackson 的 {@code SimpleBeanPropertyFilter}：后者只作用于
	 * bean 属性，对 {@code Map} 入参无效——而本项目有大量 {@code Map} 入参接口
	 * （如 {@code AiHealthController.saveProfile(Map)}、{@code BankController.ocr(Map)}），
	 * 用 filter 会让这些接口的敏感字段原文落库。
	 */
	private static JsonNode mask(JsonNode node, Set<String> excluded) {
		if (node instanceof ObjectNode objectNode) {
			Iterator<String> names = objectNode.propertyNames().iterator();
			List<String> toMask = new java.util.ArrayList<>();
			while (names.hasNext()) {
				String name = names.next();
				if (isExcluded(name, excluded)) {
					toMask.add(name);
				}
				else {
					mask(objectNode.get(name), excluded);
				}
			}
			toMask.forEach(name -> objectNode.put(name, MASKED));
		}
		else if (node instanceof ArrayNode arrayNode) {
			arrayNode.forEach(child -> mask(child, excluded));
		}
		return node;
	}

	private static boolean isExcluded(String fieldName, Set<String> excluded) {
		if (fieldName == null) {
			return false;
		}
		String lower = fieldName.toLowerCase();
		// 名字里带 password 的一律打码：精确名单漏过 newpassword1 / newPassword，改密日志曾把新密码明文写进 sys_log
		return excluded.contains(lower) || lower.contains("password");
	}

	private static Set<String> excludedFields(BaseLogProperties properties) {
		List<String> configured = properties.getExcludeFields();
		if (configured == null) {
			return Set.of();
		}
		return configured.stream()
				.filter(StrUtil::isNotBlank)
				.map(field -> field.trim().toLowerCase())
				.collect(java.util.stream.Collectors.toUnmodifiableSet());
	}

	private static boolean isSkippedType(Class<?> type) {
		return SKIPPED_PARAM_TYPES.stream().anyMatch(skipped -> skipped.isAssignableFrom(type));
	}

	@SuppressWarnings("unchecked")
	private static <A extends Annotation> A findAnnotation(Annotation[] annotations, Class<A> type) {
		for (Annotation annotation : annotations) {
			if (type.isInstance(annotation)) {
				return (A) annotation;
			}
		}
		return null;
	}

	private static String parameterName(String[] parameterNames, int index) {
		return parameterNames != null && index < parameterNames.length ? parameterNames[index] : "arg" + index;
	}

	private static String truncate(String text, Integer maxLength) {
		if (maxLength == null || maxLength <= 0 || text.length() <= maxLength) {
			return text;
		}
		int keep = Math.max(0, maxLength - TRUNCATED_SUFFIX.length());
		return text.substring(0, keep) + TRUNCATED_SUFFIX;
	}
}
