package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * [P3-6·R-C] 契约本地实现的三条硬约束。
 *
 * <h2>为什么这三条必须是门禁而不是「注意一下」</h2>
 * <p>阶段 3 要给 52 个契约接口写本地实现。三件事错了都<b>不会在编译期或日志里报出来</b>：
 * <ol>
 * <li>漏 {@code @Transactional(REQUIRES_NEW)} → 被调方<b>加入调用方的事务</b>。
 *     今天 HTTP 跨进程天然是独立事务，装进一个进程后调用方一回滚会把被调方一起带走 ——
 *     <b>只在出错路径上暴露</b>，正常流量测不出来。</li>
 * <li>漏 {@link com.zhiflow.common.security.internal.InternalCallContext} →
 *     {@code @Inside} 端点 403。安全切面读的是外层那个 C 端请求，它的 {@code from}
 *     早被网关剥掉了。</li>
 * <li>漏 {@code @Primary} → 注入点仍然拿到 HTTP 代理，<b>本地实现写了等于没写</b>：
 *     单体形态下变成自己 HTTP 调自己，而且一切正常、没人会发现。</li>
 * </ol>
 *
 * <p>本规则在写第一个本地实现（{@code ConfigStorageLocalApi}）的同一批次上线 ——
 * 有实物才能反向验证它会红。<b>先立一条永远为真的规则，是本仓反复在防的假绿。</b>
 */
class ContractLocalImplGateTest {

	/** 契约接口所在的包（实测：`@HttpExchange` 只出现在这几个包下）。 */
	private static final Set<String> CONTRACT_PACKAGES = Set.of(
			"com.zhiflow.api.", "com.zhiflow.admin.mall.", "com.zhiflow.im.api.",
			"com.zhiflow.live.admin.api.", "com.zhiflow.live.common.http.");

	private static final String TRANSACTIONAL = "org.springframework.transaction.annotation.Transactional";

	private static final String PRIMARY = "org.springframework.context.annotation.Primary";

	/** 服务端 controller 的标记：带这些注解的即使 implements 契约接口，也是被调方而非本地实现。 */
	private static final Set<String> CONTROLLER_ANNOTATIONS = Set.of(
			"org.springframework.web.bind.annotation.RestController",
			"org.springframework.stereotype.Controller");

	/** Object 自带的方法不算契约方法。 */
	private static final Set<String> OBJECT_METHODS = Set.of("equals", "hashCode", "toString");

	private static final String INTERNAL_CALL_CONTEXT =
			"com.zhiflow.common.security.internal.InternalCallContext";

	private static final String QUALIFIER = "org.springframework.beans.factory.annotation.Qualifier";

	/** 持有其中之一 = 它把调用发到进程外，不可能是"进程内直调 controller"的本地实现。 */
	private static final Set<String> GRPC_CHANNEL_TYPES = Set.of("io.grpc.Channel", "io.grpc.ManagedChannel");

	/** 进程内直调 controller 的本地实现。R-C1/C2/C4 只管这一类。 */
	private static List<JavaClass> localImpls;

	/** 转调**同接口另一个实现**的装饰器（如 mall-api 的 Resilient*Api）。见 {@link #collect()}。 */
	private static List<JavaClass> decorators;

	/** 用**另一种网络传输**实现同一契约的客户端（如 im 的 AiGoodsGrpcClientApi）。见 {@link #collect()}。 */
	private static List<JavaClass> transportClients;

	@BeforeAll
	static void collect() {
		JavaClasses classes = ArchScope.importAll();
		localImpls = new ArrayList<>();
		decorators = new ArrayList<>();
		transportClients = new ArrayList<>();
		for (JavaClass type : classes) {
			if (type.isInterface() || type.getModifiers().toString().contains("ABSTRACT")) {
				continue;
			}
			// ⚠️ 服务端的 controller 也会 implements 契约接口（im 的 *InsideController 就是这么写的，
			// 好处是路径与签名跟着接口走、不会漂）。那是**被调方**，不是本地实现 ——
			// 本地实现是调用方进程里替代 HTTP 代理的那个 bean。判据：带 @RestController/@Controller 的排除。
			boolean isController = type.getAnnotations().stream()
					.map(a -> a.getRawType().getName())
					.anyMatch(n -> CONTROLLER_ANNOTATIONS.contains(n));
			boolean implementsContract = type.getAllRawInterfaces().stream()
					.anyMatch(i -> CONTRACT_PACKAGES.stream().anyMatch(p -> i.getName().startsWith(p)));
			if (!implementsContract || isController) {
				continue;
			}
			// ⚠️ 第三类：**同接口装饰器**（2026-08-20 加）。
			//
			// 它 implements 契约接口、也不是 controller，形状上与本地实现一模一样，但它把调用
			// **转给同一个接口的另一个实现**（通常就是原来的 HTTP 代理），而不是进程内直调 controller。
			// mall-api 的 ResilientConfigStorageApi / ResilientTenantApi 就是这一类：
			// upms 发版重启时用上次成功值兜底，正常时原样透传。
			//
			// 对这一类，R-C1/C2/C4 全部**语义上不适用**，硬套只会逼人写出错的代码：
			//   R-C1 REQUIRES_NEW —— 它发的是 HTTP，本来就是独立事务，加了只是白占一条连接；
			//   R-C2 InternalCallContext —— 它把 from 原样透传给真实 HTTP 调用，头本来就在；
			//   R-C4 必须待在目标服务模块 —— 恰恰相反，装饰器**必须**待在调用方模块里。
			//
			// 判据是结构性的、不靠命名：**有一个字段的类型就是它自己实现的那个契约接口**。
			// 本地实现持有的是 controller（如 SysConfigStorageController），永远不会命中。
			boolean delegatesToSameContract = type.getFields().stream()
					.anyMatch(f -> type.getAllRawInterfaces().stream()
							.anyMatch(i -> i.getName().equals(f.getRawType().getName())));
			// ⚠️ 第四类：**另一种网络传输的客户端**（2026-09-13 P3 加）。
			//
			// 它 implements 契约接口、不是 controller、也不持有同接口字段，
			// 形状上与本地实现一模一样 —— 但它做的事恰恰相反：**把调用发到进程外去**。
			// im 的 AiGoodsGrpcClientApi 就是这一类（REST 客户端是 HttpExchange 动态代理，
			// 没有源码类，所以此前一直没撞上这条规则；gRPC 客户端是手写类，撞上了）。
			//
			// 对这一类，R-C1/C2/C3/C4 全部**语义上不适用**，硬套只会逼人写出错的代码：
			//   R-C1 REQUIRES_NEW      —— 它发的是网络调用，本来就是独立事务；
			//   R-C2 InternalCallContext —— 它把 from 显式放进 gRPC metadata 发出去，
			//                              被调方收到的是一个全新请求，不需要换本地请求上下文；
			//   R-C3 @Primary          —— **恰恰不能有**。@Primary 属于传输路由
			//                              （AiGoodsApiRouter），客户端只是它的一个候选；
			//                              给客户端也加 @Primary 就是两个 @Primary 撞车。
			//   R-C4 待在目标服务模块    —— 恰恰相反，传输客户端**必须**待在调用方模块里。
			//
			// 判据是结构性的、不靠命名：**持有一个 io.grpc.Channel 字段**。
			// 一个持有 gRPC channel 的类，在语义上不可能是"进程内直调 controller"的本地实现。
			boolean speaksOverGrpcChannel = type.getFields().stream()
					.anyMatch(f -> GRPC_CHANNEL_TYPES.contains(f.getRawType().getName()));
			if (speaksOverGrpcChannel) {
				transportClients.add(type);
			}
			else if (delegatesToSameContract) {
				decorators.add(type);
			}
			else {
				localImpls.add(type);
			}
		}
	}

	@Test
	@DisplayName("R-C0 判据自检：至少扫得到一个契约本地实现（0 个的话下面三条全是假绿）")
	void gateSeesAtLeastOneLocalImpl() {
		assertFalse(localImpls.isEmpty(), """
				一个契约本地实现都没扫到。要么是 P3-2 还没开始（那本规则应当随第一个实现一起上线），
				要么是判据失效了（包名变了 / 模块没编译）——后者会让这条门禁静默变成永远通过。

				⚠️ 也可能是 2026-08-20 加的「同接口装饰器」分流把它们全吃掉了：
				本地实现若错误地持有一个契约接口类型的字段，就会被判成装饰器而绕过 R-C1/C2/C4。
				先看 collect() 里 delegatesToSameContract 那段。""");
	}

	@Test
	@DisplayName("R-C1 契约本地实现必须 @Transactional(REQUIRES_NEW)（否则会加入调用方事务，只在回滚时暴露）")
	void mustDeclareRequiresNew() {
		List<String> offenders = new ArrayList<>();
		for (JavaClass type : localImpls) {
			for (JavaMethod method : contractMethodsOf(type)) {
				if (!hasRequiresNew(method) && !hasRequiresNew(type)) {
					offenders.add(type.getSimpleName() + "#" + method.getName());
				}
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些契约本地实现没有 @Transactional(propagation = REQUIRES_NEW)：
				%s
				今天 HTTP 跨进程调用天然是独立事务；装进同一个进程后不显式隔离，
				被调方就会加入调用方的事务 —— 调用方回滚会把被调方一起回滚掉。
				这条**只在出错路径上暴露**，正常流量永远测不出来。""".formatted(String.join("\n", offenders)));
	}

	@Test
	@DisplayName("R-C2 契约本地实现必须走 InternalCallContext（否则 @Inside 端点 403）")
	void mustRunUnderInternalCallContext() {
		List<String> offenders = new ArrayList<>();
		for (JavaClass type : localImpls) {
			boolean uses = type.getMethods().stream()
					.flatMap(m -> m.getMethodCallsFromSelf().stream())
					.anyMatch(call -> INTERNAL_CALL_CONTEXT.equals(call.getTargetOwner().getName()));
			if (!uses) {
				offenders.add(type.getName());
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些契约本地实现没有用 InternalCallContext 包住对 controller 的调用：
				%s
				@Inside 端点靠请求头 from 判内外，而进程内直调时安全切面读到的是
				**外层那个 C 端请求** —— 它的 from 早被网关剥掉了 ⇒ 403。""".formatted(
						String.join("\n", offenders)));
	}

	/**
	 * R-C3 对**两类**都成立：本地实现和装饰器都是「替代注入点上那个 HTTP 代理」，
	 * 少了 {@code @Primary} 就都是写了等于没写。所以这里不分流。
	 */
	@Test
	@DisplayName("R-C3 契约本地实现/装饰器必须 @Primary（否则注入点仍拿到 HTTP 代理，写了等于没写）")
	void mustBePrimary() {
		List<String> offenders = new ArrayList<>();
		List<JavaClass> all = new ArrayList<>(localImpls);
		all.addAll(decorators);
		for (JavaClass type : all) {
			if (type.getAnnotations().stream().noneMatch(a -> PRIMARY.equals(a.getRawType().getName()))) {
				offenders.add(type.getName());
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些契约本地实现没有 @Primary：
				%s
				HTTP 代理 bean 仍然存在，注入点会继续拿到它 —— 本地实现写了等于没写，
				单体形态下变成自己 HTTP 调自己，而且一切正常、没人会发现。""".formatted(
						String.join("\n", offenders)));
	}

	/**
	 * R-C5 是给装饰器补的、与 R-C4「防无限递归」同源的一条。
	 *
	 * <p>装饰器自己就是 {@code @Primary}，它要注入的却是**被它顶掉的那个**原始 HTTP bean。
	 * 不显式 {@code @Qualifier} 指名，Spring 按类型选就会选中 {@code @Primary} 的它自己 ——
	 * 轻则循环引用起不来，重则每次调用无限自递归。
	 *
	 * <p>这与 R-C4 记的 2026-08-16 那次 {@code TokenApi} 事故是同一种失败：
	 * {@code @Primary} 让「转发」变成了「调回自己」。
	 */
	@Test
	@DisplayName("R-C5 同接口装饰器必须 @Qualifier 指名被装饰的 bean（否则 @Primary 让它注入自己 → 无限递归）")
	void decoratorsMustQualifyDelegate() {
		List<String> offenders = new ArrayList<>();
		for (JavaClass type : decorators) {
			boolean qualified = type.getConstructors().stream()
					.flatMap(c -> c.getParameterAnnotations().stream())
					.flatMap(java.util.Collection::stream)
					.anyMatch(a -> QUALIFIER.equals(((JavaAnnotation<?>) a).getRawType().getName()));
			if (!qualified) {
				offenders.add(type.getName());
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些同接口装饰器没有在构造器参数上 @Qualifier 指名被装饰的 bean：
				%s
				装饰器自己是 @Primary，Spring 按类型选会选中它自己 ——
				轻则循环引用起不来，重则调用无限自递归。
				与 R-C4 记的 TokenApi 事故同源：@Primary 把「转发」变成了「调回自己」。""".formatted(
						String.join("\n", offenders)));
	}

	/**
	 * R-C6 是给「另一种传输的客户端」补的，方向与 R-C3 <b>相反</b>。
	 *
	 * <p>{@code @Primary} 归传输路由（如 {@code AiGoodsApiRouter}）所有：注入点该拿到的是
	 * 「按开关选传输的那个」，而不是其中某一种传输。给客户端也挂 {@code @Primary}，
	 * 轻则两个 {@code @Primary} 撞成 {@code NoUniqueBeanDefinitionException} 起不来，
	 * 重则路由被绕过去 —— <b>开关从此形同虚设，回滚按钮按下去没反应</b>，
	 * 而这正是灰度期唯一的退路。
	 */
	@Test
	@DisplayName("R-C6 传输客户端**不得** @Primary（@Primary 属于传输路由，挂错了回滚开关会失效）")
	void transportClientsMustNotBePrimary() {
		List<String> offenders = new ArrayList<>();
		for (JavaClass type : transportClients) {
			if (type.getAnnotations().stream().anyMatch(a -> PRIMARY.equals(a.getRawType().getName()))) {
				offenders.add(type.getName());
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				这些传输客户端挂了 @Primary：
				%s
				@Primary 归传输路由所有。客户端也挂上之后：要么两个 @Primary 撞成
				NoUniqueBeanDefinitionException 起不来，要么路由被绕过去 ——
				传输开关形同虚设，而它是灰度期唯一的回滚按钮。""".formatted(String.join("\n", offenders)));
	}

	@Test
	@DisplayName("R-C4 本地实现必须待在**该契约目标服务**的模块里（放错模块 = 调回自己，可能无限递归）")
	void mustLiveInTargetServiceModule() throws java.io.IOException {
		java.util.Map<String, String> targetOf = contractTargets();
		List<String> offenders = new ArrayList<>();
		for (JavaClass type : localImpls) {
			String api = type.getAllRawInterfaces().stream()
					.filter(i -> CONTRACT_PACKAGES.stream().anyMatch(p -> i.getName().startsWith(p)))
					.map(JavaClass::getSimpleName).findFirst().orElse(null);
			if (api == null) {
				continue;
			}
			String target = targetOf.get(api);
			if (target == null) {
				// 该契约在全仓没有任何 createClient 注册 ⇒ 没有"目标服务"可比，本规则对它无从判定。
				// 这是合法状态：契约只在进程内用（只有 LocalApi、没有远程客户端）时就是这样。
				// ⚠️ 必须显式跳过：SERVICE_PACKAGE 是 Map.of，get(null) 直接 NPE ——
				//    2026-09-13 清死契约注册时实测撞到（GoodsSpuApi 变成这种状态），
				//    门禁不是红而是**崩**，报错信息完全指不到真正的原因。
				// 「注入了却无 bean 可解析」这一类由 ContractClientRegistrationGateTest R-CR1 负责。
				continue;
			}
			String expectedPackage = SERVICE_PACKAGE.get(target);
			if (expectedPackage != null && !type.getName().startsWith(expectedPackage)) {
				offenders.add(type.getName() + " 实现的 " + api + " 目标服务是 " + target
						+ "（应在 " + expectedPackage + " 之下）");
			}
		}
		assertTrue(offenders.isEmpty(), () -> """
				本地实现放错了模块：
				%s
				判据不能只看「哪个 controller 的路径对得上」——**同一条路径在两个服务里都可能存在**。
				2026-08-16 实测：TokenApi 的目标服务是 auth，而 upms-admin 恰好也有一个
				@RequestMapping("/token") 的 controller，按路径生成就把本地实现放进了 upms；
				更糟的是 upms 那个 TokenController **自己就注入 TokenApi 去调 auth** ——
				@Primary 一挂就变成调回自己，**无限递归**。""".formatted(String.join("\n", offenders)));
	}

	/** 服务名常量 → 该服务的根包。 */
	private static final java.util.Map<String, String> SERVICE_PACKAGE = java.util.Map.of(
			"UMPS_ADMIN_SERVICE", "com.zhiflow.upms.admin",
			"MALL_ADMIN_SERVICE", "com.zhiflow.mall.admin",
			"MALL_API_SERVICE", "com.zhiflow.mall.api",
			"WX_ADMIN_SERVICE", "com.zhiflow.weixin.admin",
			"ZHIFLOW_PAY_ADMIN", "com.zhiflow.pay.admin",
			"LIVE_ADMIN_SERVICE", "com.zhiflow.live.admin",
			"AUTH_SERVICE", "com.zhiflow.auth",
			"ZHIFLOW_IM_SERVICE", "com.zhiflow.im");

	/** 从全仓的 createClient(ServiceNameConstants.X, YApi.class) 里现扫「契约 → 目标服务」。 */
	private static java.util.Map<String, String> contractTargets() throws java.io.IOException {
		java.util.regex.Pattern pattern = java.util.regex.Pattern
				.compile("createClient\\(\\s*ServiceNameConstants\\.([A-Z_]+)\\s*,\\s*(\\w+)\\.class");
		java.util.Map<String, String> targets = new java.util.HashMap<>();
		java.nio.file.Path root = java.nio.file.Path.of("..").toAbsolutePath().normalize();
		try (java.util.stream.Stream<java.nio.file.Path> files = java.nio.file.Files.walk(root)) {
			for (java.nio.file.Path file : files.filter(f -> f.toString().endsWith(".java"))
					.filter(f -> !f.toString().contains("/target/"))
					// ⚠️ .claude/worktrees 下是整份仓库副本（常是别的分支），算进来会让
					// 「契约目标服务」表混入别人分支的定义，判定随磁盘上有哪些 worktree 而变。
					.filter(f -> !f.toString().contains("/.claude/")).toList()) {
				java.util.regex.Matcher matcher = pattern
						.matcher(java.nio.file.Files.readString(file, StandardCharsets.UTF_8));
				while (matcher.find()) {
					targets.put(matcher.group(2), matcher.group(1));
				}
			}
		}
		assertFalse(targets.isEmpty(), "扫不到任何 createClient 注册 —— 判据失效，本规则会静默通过");
		return targets;
	}

	// ------------------------------------------------------------ helpers

	/**
	 * 本地实现的「契约方法」= 它自己声明的全部 public 方法。
	 *
	 * <p>⚠️ <b>不能靠「去问契约接口有哪些方法」</b>：{@link ArchScope} 只扫 8 个服务模块的
	 * target/classes，{@code zhiflow-api-*} 不在扫描面里 —— 契约接口在这里是个<b>没有方法的空壳</b>，
	 * 拿它取交集会得到空集合，规则于是永远通过。
	 * 2026-08-16 本条正是这么被反向验证抓出来的：把样板实现的传播级别改成 {@code REQUIRED}
	 * 之后门禁依然全绿。<b>一条不会红的规则比没有规则更危险</b>，它会让后面 51 个实现都以为审过了。
	 */
	private static List<JavaMethod> contractMethodsOf(JavaClass type) {
		return type.getMethods().stream()
				.filter(m -> m.getModifiers().contains(JavaModifier.PUBLIC))
				.filter(m -> !OBJECT_METHODS.contains(m.getName()))
				.toList();
	}

	private static boolean hasRequiresNew(JavaMethod method) {
		return hasRequiresNew(method.getAnnotations());
	}

	private static boolean hasRequiresNew(JavaClass type) {
		return hasRequiresNew(type.getAnnotations());
	}

	private static boolean hasRequiresNew(Set<? extends JavaAnnotation<?>> annotations) {
		Optional<? extends JavaAnnotation<?>> transactional = annotations.stream()
				.filter(a -> TRANSACTIONAL.equals(a.getRawType().getName()))
				.findFirst();
		return transactional
				.map(a -> a.get("propagation")
						.map(value -> String.valueOf(value).endsWith("REQUIRES_NEW"))
						.orElse(false))
				.orElse(false);
	}
}
