package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 业务代码不许直接调<b>单参</b>的 {@code CompletableFuture.runAsync / supplyAsync}。
 *
 * <p>单参版本落在公共 ForkJoin 池，池线程是复用的，{@code TransmittableThreadLocal} 只在建线程那一刻
 * 拷贝一次 ⇒ 任务读到的是「最早建这条池线程的那个请求」的租户：查库落错 schema，
 * 内部调用经 {@code TenantRequestInterceptor} 把旧租户写进请求头（9/14、9/15 支付回调后发通知失败即此）。
 *
 * <p>改法：走 {@code TenantAwareAsync.runAsync / supplyAsync}（公共池 + 提交时抓取上下文），
 * 或显式传一个做了 TTL 包装 / 每任务新线程的执行器。
 *
 * <p>目标是 0 违例，不用 {@code FreezingArchRule}：没有需要冻结的存量。
 */
class CommonPoolAsyncGateTest {

	private static ArchRule rule() {
		return noClasses()
			.should().callMethod(CompletableFuture.class, "runAsync", Runnable.class)
			.orShould().callMethod(CompletableFuture.class, "supplyAsync", Supplier.class)
			.because("公共 ForkJoin 池线程带的是建线程那一刻的租户；改用 TenantAwareAsync 或显式传 TTL 执行器");
	}

	@Test
	@DisplayName("业务模块不直接调用单参 runAsync / supplyAsync")
	void noSingleArgCommonPoolAsync() {
		rule().check(ArchScope.importAll());
	}

	/** 对照样本：规则能认出两种单参写法，且放过带执行器的写法。 */
	@Test
	@DisplayName("对照样本：单参被拦、带执行器的放行")
	void ruleRecognisesViolations() {
		JavaClasses samples = new ClassFileImporter().importClasses(Violating.class, Allowed.class);

		EvaluationResult result = rule().evaluate(samples);

		String report = String.join("\n", result.getFailureReport().getDetails());
		assertTrue(report.contains("Violating") && report.contains("runAsync") && report.contains("supplyAsync"),
				"单参两种写法都应被拦下，实际报告：\n" + report);
		assertTrue(!report.contains("Allowed"), "带执行器的写法不应被拦，实际报告：\n" + report);
	}

	static class Violating {
		void run() {
			CompletableFuture.runAsync(() -> {
			});
			CompletableFuture.supplyAsync(() -> "x");
		}
	}

	static class Allowed {
		void run() {
			CompletableFuture.runAsync(() -> {
			}, Runnable::run);
			CompletableFuture.supplyAsync(() -> "x", Runnable::run);
		}
	}

}
