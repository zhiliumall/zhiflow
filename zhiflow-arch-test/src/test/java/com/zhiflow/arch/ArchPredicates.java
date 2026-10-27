package com.zhiflow.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;

import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * 门禁判定谓词。
 *
 * <p>一律用注解全限定名字符串判定，不 import Spring / 业务注解类型：门禁模块因此不依赖任何
 * 业务模块或 Spring 版本，只依赖 ArchUnit 本身。被扫 class 里注解类型缺失也不会让规则崩。
 */
final class ArchPredicates {

	static final String ANN_REST_CONTROLLER = "org.springframework.web.bind.annotation.RestController";
	static final String ANN_CONTROLLER = "org.springframework.stereotype.Controller";
	static final String ANN_REQUEST_MAPPING = "org.springframework.web.bind.annotation.RequestMapping";
	static final String ANN_GET_MAPPING = "org.springframework.web.bind.annotation.GetMapping";
	static final String ANN_SYS_LOG = "com.zhiflow.common.log.annotation.SysLog";
	static final String ANN_PRE_AUTHORIZE = "org.springframework.security.access.prepost.PreAuthorize";

	/**
	 * C 端登录校验注解。
	 *
	 * <p>项目有<b>两套并行的鉴权体系</b>，不能用同一个判据要求：
	 * <ul>
	 *   <li>后台服务走 Spring Security 权限码：{@code @PreAuthorize("@ato.hasAuthority('xxx')")}
	 *       （im-server 55 处、mall-admin 等）；</li>
	 *   <li>C 端走第三方 session：{@code @ApiLogin} + {@code ThirdSessionHolder}
	 *       （mall-api <b>332 处 @ApiLogin，仅 1 处 @PreAuthorize</b>）。</li>
	 * </ul>
	 * C 端用户没有后台权限码，给 C 端接口挂 {@code @PreAuthorize} 会让所有 C 端功能直接 403。
	 * 因此判据是「两者之一」，而不是只认 {@code @PreAuthorize}——剩下的才是真正无鉴权的裸接口。
	 */
	static final String ANN_API_LOGIN = "com.zhiflow.common.portal.annotation.ApiLogin";
	static final String ANN_TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

	/** 写语义的 HTTP mapping 注解 */
	private static final Set<String> WRITE_MAPPING_ANNOTATIONS = Set.of(
			"org.springframework.web.bind.annotation.PostMapping",
			"org.springframework.web.bind.annotation.PutMapping",
			"org.springframework.web.bind.annotation.DeleteMapping",
			"org.springframework.web.bind.annotation.PatchMapping");

	/** {@code @RequestMapping(method = ...)} 里视为写语义的取值 */
	private static final Set<String> WRITE_HTTP_METHODS = Set.of("POST", "PUT", "DELETE", "PATCH");

	/**
	 * 持久层写方法名前缀（MyBatis-Plus IService / BaseMapper 口径）。
	 *
	 * <p>{@code physicalRemove} 必须在列表里：S8 把物理删实体的删除改成了 Mapper 上的
	 * {@code physicalRemoveById} / {@code physicalRemoveByIds} 裸 SQL，而这些名字既不以
	 * {@code remove} 开头也不以 {@code delete} 开头 —— 漏掉它会让 R2（跨 ≥2 持久化目标须带
	 * {@code @Transactional}）在每做一次物理删改造后静默缩水。实测：
	 * {@code H5PortalDomainServiceImpl.createDefault}（物理删旧默认域名 + insert 新行、
	 * 至今无 {@code @Transactional}）就因此从违例清单里消失过。
	 */
	private static final List<String> PERSISTENCE_WRITE_PREFIXES = List.of(
			"save", "update", "insert", "remove", "delete", "physicalRemove");

	/** 物理删方法名：R3 的拦截目标 */
	static final Set<String> PHYSICAL_DELETE_METHODS = Set.of(
			"removeById", "removeByIds", "deleteById", "deleteBatchIds", "removeBatchByIds");

	/** 允许物理删的调用方方法名前缀：显式声明意图 */
	private static final String PHYSICAL_REMOVE_INTENT_PREFIX = "physicalRemove";

	/** Service 层写方法名前缀：R2 的候选范围 */
	private static final List<String> SERVICE_WRITE_PREFIXES = List.of(
			"save", "update", "delete", "remove", "insert", "create", "modify", "batch", "grant", "settle");

	private ArchPredicates() {
	}

	/** C 端服务包前缀（与 {@code ArchExemptions.C_END_PACKAGE_MARKER} 同一口径） */
	private static final String MALL_API_PACKAGE = "com.zhiflow.mall.api.";

	static final DescribedPredicate<JavaMethod> WRITE_ENDPOINT = new DescribedPredicate<>(
			"是 Controller 上的写语义 HTTP 端点") {
		@Override
		public boolean test(JavaMethod method) {
			return isInController(method.getOwner()) && isWriteMapping(method);
		}
	};

	/**
	 * 限定在 mall-api（C 端）。R1c 只管这一个模块，因为「类级 {@code @ApiLogin} 运行期不生效」
	 * 这个陷阱是 {@code ThirdSessionInterceptor} 独有的，后台服务走 Spring Security，不适用。
	 */
	static final DescribedPredicate<JavaMethod> IN_MALL_API = new DescribedPredicate<>("属于 mall-api C 端") {
		@Override
		public boolean test(JavaMethod method) {
			return method.getOwner().getFullName().startsWith(MALL_API_PACKAGE);
		}
	};

	/**
	 * 用 GET 表达写语义的端点：{@code @GetMapping} 却在方法体里直接调持久层写方法。
	 *
	 * <p><b>为什么单独立一条</b>：{@link #WRITE_ENDPOINT} 按 HTTP 动词判，
	 * 所以 R1a/R1b/R1c 对这一整类**结构性失明**。实际代码里的例子
	 * {@code GET /mallapi/globalhotword?word=}（搜索埋点，落 {@code global_hot_word} 表，
	 * 匿名可写任意 ≤50 字文本，且该表内容经 {@code /getTop} 展示给全体用户）就是这形态，
	 * 三条门禁一条都抓不到它。
	 *
	 * <p><b>只判直接调用（深度 1）</b>：故意不做传递闭包。传递分析会把「商详页异步记足迹」
	 * 这类正常读侧埋点全部拖进来（实测 3 跳内命中 9 个端点，其中 7 个是缓存/计数），
	 * 规则一旦噪声大就会被塞进 baseline 永久失效。深度 1 精确覆盖「controller 里直接写库」
	 * 这个真正危险的形态：写动作近在眼前却被 GET 的外衣盖住。
	 */
	static final DescribedPredicate<JavaMethod> READ_ENDPOINT_WITH_PERSISTENCE_WRITE = new DescribedPredicate<>(
			"是 GET 端点但方法体里直接调用持久层写方法") {
		@Override
		public boolean test(JavaMethod method) {
			return isInController(method.getOwner())
					&& isReadMapping(method)
					&& method.getMethodCallsFromSelf().stream().anyMatch(ArchPredicates::isPersistenceWrite);
		}
	};

	static final DescribedPredicate<JavaMethod> SERVICE_MULTI_TABLE_WRITE = new DescribedPredicate<>(
			"是 ServiceImpl 中跨 ≥2 个持久化目标的写方法") {
		@Override
		public boolean test(JavaMethod method) {
			if (!method.getOwner().getSimpleName().endsWith("ServiceImpl")) {
				return false;
			}
			if (!startsWithAny(method.getName(), SERVICE_WRITE_PREFIXES)) {
				return false;
			}
			return distinctPersistenceWriteTargets(method) >= 2;
		}
	};

	/** 方法或其所在类带 {@code @Transactional} 均算满足（Spring 类级注解对 public 方法生效） */
	static boolean isTransactional(JavaMethod method) {
		return method.isAnnotatedWith(ANN_TRANSACTIONAL)
				|| method.getOwner().isAnnotatedWith(ANN_TRANSACTIONAL);
	}

	static boolean isPhysicalDeleteCall(JavaMethodCall call) {
		if (!PHYSICAL_DELETE_METHODS.contains(call.getTarget().getName())) {
			return false;
		}
		// 显式声明物理删意图的方法内部放行
		return !call.getOrigin().getName().startsWith(PHYSICAL_REMOVE_INTENT_PREFIX);
	}

	private static boolean isInController(JavaClass owner) {
		return owner.isAnnotatedWith(ANN_REST_CONTROLLER) || owner.isAnnotatedWith(ANN_CONTROLLER);
	}

	/** 只认显式 {@code @GetMapping}；不限 method 的 {@code @RequestMapping} 已被 {@link #isWriteMapping} 从严收走 */
	private static boolean isReadMapping(JavaMethod method) {
		return method.isAnnotatedWith(ANN_GET_MAPPING) && !isWriteMapping(method);
	}

	private static boolean isWriteMapping(JavaMethod method) {
		if (WRITE_MAPPING_ANNOTATIONS.stream().anyMatch(method::isAnnotatedWith)) {
			return true;
		}
		return writeMethodOnRequestMapping(method);
	}

	/**
	 * 处理 {@code @RequestMapping(method = RequestMethod.POST)} 形式。
	 * 属性值为枚举常量数组，通过 ArchUnit 的 JavaAnnotation 反射式读取，避免依赖 spring-web。
	 */
	private static boolean writeMethodOnRequestMapping(JavaMethod method) {
		if (!method.isAnnotatedWith(ANN_REQUEST_MAPPING)) {
			return false;
		}
		JavaAnnotation<?> annotation = method.getAnnotations().stream()
				.filter(a -> a.getRawType().getName().equals(ANN_REQUEST_MAPPING))
				.findFirst()
				.orElse(null);
		if (annotation == null) {
			return false;
		}
		Optional<Object> value = annotation.tryGetExplicitlyDeclaredProperty("method");
		if (value.isEmpty()) {
			// 未声明 method 的 @RequestMapping 接受任意方法，含写语义 —— 从严处理
			return true;
		}
		Object raw = value.get();
		Object[] entries = raw instanceof Object[] array ? array : new Object[] { raw };
		return Arrays.stream(entries)
				.map(String::valueOf)
				.anyMatch(s -> WRITE_HTTP_METHODS.stream().anyMatch(s::endsWith));
	}

	private static long distinctPersistenceWriteTargets(JavaMethod method) {
		return method.getMethodCallsFromSelf().stream()
				.filter(ArchPredicates::isPersistenceWrite)
				.map(call -> call.getTargetOwner().getName())
				.distinct()
				.count();
	}

	private static boolean isPersistenceWrite(JavaMethodCall call) {
		String ownerName = call.getTargetOwner().getSimpleName();
		boolean persistenceOwner = ownerName.endsWith("Mapper") || ownerName.endsWith("Service")
				|| ownerName.endsWith("ServiceImpl");
		return persistenceOwner && startsWithAny(call.getTarget().getName(), PERSISTENCE_WRITE_PREFIXES);
	}

	private static boolean startsWithAny(String name, List<String> prefixes) {
		return prefixes.stream().anyMatch(name::startsWith);
	}
}
