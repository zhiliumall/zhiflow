package com.zhiflow.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaParameterizedType;
import com.tngtech.archunit.core.domain.JavaType;

import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.Optional;

/**
 * R4 支撑：识别批量端点，并判定其返回类型是否承载 {@code BatchResult}。
 *
 * <p>项目 Controller 统一返回 {@code R<T>}，raw 返回类型永远是 {@code R}，因此必须看泛型实参。
 * class 文件的 Signature 属性保留了泛型信息，ArchUnit 通过 {@link JavaParameterizedType} 暴露。
 */
final class BatchEndpoints {

	private static final String BATCH_RESULT_SIMPLE_NAME = "BatchResult";

	/** 批量语义标记：出现在方法名或 mapping 路径里 */
	private static final String BATCH_MARKER = "batch";

	private static final String[] PATH_PROPERTIES = { "value", "path" };

	/**
	 * 批量<b>查询</b>动词。这类端点用 POST 只是为了传 List 入参，返回的是数据集合而非逐条处理结果，
	 * 要求它们返回 {@code BatchResult} 是误报（实测命中 batchGet / batchQueryMapView /
	 * batchGetByOrderIds 等 4 处）。部分成功语义只适用于批量<b>变更</b>。
	 */
	private static final List<String> READ_VERBS = List.of(
			"get", "query", "list", "find", "search", "count", "load", "fetch", "export", "check");

	private BatchEndpoints() {
	}

	/**
	 * 逐条看过、确认不需要 {@code BatchResult} 的批量端点：
	 * <ul>
	 *   <li><b>OrderInfoController.batchRemark</b>——已返回逐条结果（{@code OrderRemarkBatchResult}），只是不叫 BatchResult。</li>
	 *   <li><b>SysLogController.saveBatchLogs</b>——审计日志批量入库，调用方（切面）是 fire-and-forget，拿到逐条结果也无从处理。</li>
	 * </ul>
	 */
	private static final Set<String> EXEMPT_METHODS = Set.of(
			"com.zhiflow.mall.admin.controller.OrderInfoController.batchRemark",
			"com.zhiflow.upms.admin.controller.SysLogController.saveBatchLogs");

	static final DescribedPredicate<JavaMethod> IS_BATCH_ENDPOINT = new DescribedPredicate<>("是批量变更端点") {
		@Override
		public boolean test(JavaMethod method) {
			if (EXEMPT_METHODS.contains(method.getOwner().getFullName() + "." + method.getName())) {
				return false;
			}
			boolean batchNamed = method.getName().toLowerCase().contains(BATCH_MARKER)
					|| mappingPathHasBatchMarker(method);
			return batchNamed && !isReadOperation(method);
		}
	};

	/**
	 * 判定读操作：方法名去掉 batch 前后缀后以读动词开头。
	 * 例：batchGetByOrderIds -> getbyorderids -> 命中 get；insertItemBatch -> insertitem -> 不命中。
	 */
	private static boolean isReadOperation(JavaMethod method) {
		String name = method.getName().toLowerCase();
		String stripped = name.startsWith(BATCH_MARKER) ? name.substring(BATCH_MARKER.length()) : name;
		if (stripped.endsWith(BATCH_MARKER)) {
			stripped = stripped.substring(0, stripped.length() - BATCH_MARKER.length());
		}
		return READ_VERBS.stream().anyMatch(stripped::startsWith);
	}

	static boolean returnsBatchResult(JavaMethod method) {
		return containsBatchResult(method.getReturnType());
	}

	private static boolean containsBatchResult(JavaType type) {
		if (type == null) {
			return false;
		}
		// 按擦除后的类名判：参数化类型的 getName() 带着泛型参数（"…BatchResult<java.lang.String>"），
		// 直接 endsWith 永远不成立 —— 2026-10-20 前 R<BatchResult<X>> 一直被判成「不含 BatchResult」
		if (type.toErasure().getName().endsWith("." + BATCH_RESULT_SIMPLE_NAME)) {
			return true;
		}
		if (type instanceof JavaParameterizedType parameterized) {
			return parameterized.getActualTypeArguments().stream().anyMatch(BatchEndpoints::containsBatchResult);
		}
		return false;
	}

	private static boolean mappingPathHasBatchMarker(JavaMethod method) {
		for (JavaAnnotation<?> annotation : method.getAnnotations()) {
			if (!annotation.getRawType().getName().startsWith("org.springframework.web.bind.annotation.")) {
				continue;
			}
			for (String property : PATH_PROPERTIES) {
				Optional<Object> value = annotation.tryGetExplicitlyDeclaredProperty(property);
				if (value.isPresent() && rawValueContainsBatch(value.get())) {
					return true;
				}
			}
		}
		return false;
	}

	private static boolean rawValueContainsBatch(Object raw) {
		Object[] entries = raw instanceof Object[] array ? array : new Object[] { raw };
		return Arrays.stream(entries)
				.map(String::valueOf)
				.anyMatch(s -> s.toLowerCase().contains(BATCH_MARKER));
	}
}
