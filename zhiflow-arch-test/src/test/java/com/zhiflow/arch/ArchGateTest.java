package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import com.tngtech.archunit.library.freeze.FreezingArchRule;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;

/**
 * 后端防回退门禁。
 *
 * <p>四条规则全部以 {@link FreezingArchRule} 落地：存量违例冻结进
 * {@code src/test/resources/archunit_store}，新增违例即失败。修好的存量会自动从 store 移除，
 * 实现「增量 > 0 阻断，存量只降不升」。
 *
 * <p>store 的 {@code allowStoreCreation} 在 {@code archunit.properties} 里设为 false：
 * 防止 store 目录意外丢失时被静默重建——那等于把 baseline 清零，是最隐蔽的门禁失效方式。
 * 首次初始化需显式加 {@code -Darchunit.freeze.store.default.allowStoreCreation=true}。
 */
@Order(1) // 必须先于 BaselineIntegrityTest：本类运行中会更新 store，后者校验落盘结果
class ArchGateTest {

	private static JavaClasses classes;

	/** 实体所在模块（*-common），与 {@link #classes} 分开扫，避免动到六条既有规则的 baseline */
	private static JavaClasses entityClasses;

	@BeforeAll
	static void importClasses() {
		classes = ArchScope.importAll();
		entityClasses = ArchScope.importEntities();
	}

	@Test
	@DisplayName("R1a 写语义 Controller 方法必须有 @SysLog")
	void writeEndpointsMustBeAudited() {
		ArchRule rule = methods()
				.that(ArchPredicates.WRITE_ENDPOINT)
				.should(beAnnotatedWithSysLogUnlessExempt())
				.because("失败与成功的写操作都要可回溯；缺 @SysLog 的写接口在 sys_log 里完全不存在");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R1b 写语义 Controller 方法必须声明鉴权（@PreAuthorize 或 @ApiLogin）")
	void writeEndpointsMustBeAuthorized() {
		ArchRule rule = methods()
				.that(ArchPredicates.WRITE_ENDPOINT)
				.should(declareAuthorizationUnlessExempt())
				.because("两套鉴权体系并行：后台走 @PreAuthorize 权限码，C 端走 @ApiLogin 第三方 session。"
						+ "两者都没有的写接口是无鉴权裸接口");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R1c mall-api 写端点必须声明方法级 @ApiLogin")
	void mallApiWriteEndpointsMustDeclareMethodLevelApiLogin() {
		ArchRule rule = methods()
				.that(ArchPredicates.WRITE_ENDPOINT)
				.and(ArchPredicates.IN_MALL_API)
				.should(declareMethodLevelApiLoginUnlessExempt())
				.because("R1b 认类级 @ApiLogin，而 ThirdSessionInterceptor 只读方法级——"
						+ "两边一叠加就是「门禁绿、运行期无鉴权」。POST /mallapi/file/upload 匿名可传 OSS "
						+ "就是这么活下来的：类上写着 @ApiLogin(mustLogin=false)，R1b 判它已声明鉴权");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R1d mall-api 的 GET 端点不得直接写库（用 GET 表达写语义）")
	void mallApiReadEndpointsMustNotWriteDirectly() {
		ArchRule rule = methods()
				.that(ArchPredicates.READ_ENDPOINT_WITH_PERSISTENCE_WRITE)
				.and(ArchPredicates.IN_MALL_API)
				.should(beRegisteredReadSideWrite())
				.because("R1a/R1b/R1c 全按 HTTP 动词判写端点，对『用 GET 表达写语义』这一类结构性失明。"
						+ "GET /mallapi/globalhotword?word= 就是这么活着的：匿名可往 global_hot_word 写"
						+ "任意 ≤50 字文本，而该表内容经 /getTop 展示给全体用户，三条门禁一条都没看见它")
				// 2026-10-20 开源分支：mall-api 已没有任何「GET 里直接写库」的端点，谓词匹配 0 个方法是期望状态。
				// 扫描面本身是否失效由 classes 的非空自检兜，这里不能再用 failOnEmptyShould 当自检。
				.allowEmptyShould(true);

		rule.check(classes);
	}

	@Test
	@DisplayName("R2 Service 跨表写方法必须有 @Transactional")
	void multiTableWritesMustBeTransactional() {
		ArchRule rule = methods()
				.that(ArchPredicates.SERVICE_MULTI_TABLE_WRITE)
				.should(beTransactional())
				.because("跨 ≥2 个持久化目标的写操作无事务时，中途失败会留下半提交状态");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R4 批量端点返回类型必须为 BatchResult")
	void batchEndpointsMustReturnBatchResult() {
		ArchRule rule = methods()
				.that(ArchPredicates.WRITE_ENDPOINT)
				.and(BatchEndpoints.IS_BATCH_ENDPOINT)
				.should(returnBatchResult())
				.because("整体成败语义会把「20 条里 3 条失败」报成全成功或全失败，逐条失败必须可见");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R5 吞掉异常并返回失败响应的方法必须调 ErrorLogRecorder.record")
	void swallowedExceptionsMustBeRecorded() {
		ArchRule rule = methods()
				.that(SWALLOWS_EXCEPTION)
				.should(recordErrorLog())
				.because("catch 后 return R.failed 的异常永不进全局异常处理器，DB 无记录、"
						+ "容器重启即失——错误日志里长期看不到 C 端 URI 就是这么来的");

		FreezingArchRule.freeze(rule).check(classes);
	}

	@Test
	@DisplayName("R6 实体的删除语义必须有人拍过板（@TableLogic 或登记物理删）")
	void everyEntityMustHaveDecidedDeleteSemantics() {
		ArchRule rule = classes()
				.that(EntityPredicates.IS_CONCRETE_BASE_ENTITY)
				.should(haveDecidedDeleteSemantics())
				.because("翻转后「这个实体是软删还是物理删」不能靠读调用点猜——"
						+ "调用点规则天然看不见 Service 内部换轨，只有实体侧能给出确定答案");

		// 不用 FreezingArchRule：本规则的目标就是 0 违例，不存在「存量技术债」需要冻结。
		// 冻结反而会把新出现的未决实体悄悄收进 baseline，正好是它要防的事。
		rule.check(entityClasses);
	}

	@Test
	@DisplayName("R7 IdType.INPUT 实体走软删必须经过「删+重插」复核")
	void inputIdEntitiesMustBeReviewedForDeleteReinsert() {
		ArchRule rule = classes()
				.that(EntityPredicates.IS_CONCRETE_BASE_ENTITY)
				.and(EntityPredicates.HAS_INPUT_ID)
				.should(bePhysicalOrReviewed())
				.because("主键即业务键时，软删的墓碑会占着主键让「删掉再按同一主键重建」直接 duplicate key；"
						+ "主键做不成部分唯一索引，V80 对这一类没有任何保护——同形态 bug 已出现四次");

		rule.check(entityClasses);
	}

	@Test
	@DisplayName("R8 带 del_flag 但不继承 BaseEntity 的实体必须登记（翻转射程外）")
	void entitiesOutsideFlipMustBeRegistered() {
		ArchRule rule = classes()
				.that(EntityPredicates.HAS_DEL_FLAG_OUTSIDE_BASE_ENTITY)
				.should(beRegisteredAsOutsideFlip())
				.because("「全量式翻转」只覆盖 BaseEntity 子类；自带 delFlag 的类翻转不生效、仍是物理删，"
						+ "不登记就会被默认成已软删");

		rule.check(entityClasses);
	}

	@Test
	@DisplayName("R9 物理删实体的 ServiceImpl 必须覆盖全部四个删除入口")
	void physicalDeleteEntitiesMustOverrideAllDeleteEntryPoints() {
		// 自证守卫：R9 靠解析 ServiceImpl 的泛型参数定位实体，一旦解析不出来，
		// 谓词匹配 0 个类，规则会「空过」——和 ArchScope 扫到 0 个 class 是同一种静默失效。
		// 这里先钉住匹配数：2 个物理删实体分布在 3 个 ServiceImpl 上（ShoppingCart 在 mall-api /
		// mall-admin 各 1 个，WxApp 1 个）。新增物理删实体时这个数要一起改。
		long matched = classes.stream()
				.filter(EntityPredicates.IS_PHYSICAL_DELETE_SERVICE_IMPL)
				.count();
		org.junit.jupiter.api.Assertions.assertEquals(3, matched,
				"R9 匹配到的 ServiceImpl 数与预期不符。要么 PhysicalDeleteRegistry 变了（同步改这个数），"
						+ "要么泛型解析失效导致规则空过——后者会让 R9 永远绿着却什么都没检查");

		ArchRule rule = classes()
				.that(EntityPredicates.IS_PHYSICAL_DELETE_SERVICE_IMPL)
				.should(overrideAllDeleteEntryPoints())
				.because("MyBatis-Plus 没有「按实体关闭 @TableLogic」的开关，"
						+ "任何没被覆盖的删除入口在翻转后都会静默变成软删；"
						+ "对主键即业务键的实体，墓碑还会让「删后重插」直接主键冲突");

		rule.check(classes);
	}

	// ---------- 实体侧 conditions ----------

	private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> overrideAllDeleteEntryPoints() {
		return new ArchCondition<>("override removeById / removeByIds / removeBatchByIds / remove(Wrapper)") {
			@Override
			public void check(com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
				for (String method : EntityPredicates.DELETE_ENTRY_POINTS) {
					boolean overridden = javaClass.getMethods().stream()
							.anyMatch(m -> m.getName().equals(method));
					events.add(new SimpleConditionEvent(javaClass, overridden,
							("%s 未覆盖 %s：%s 是物理删实体（PhysicalDeleteRegistry），"
									+ "该入口会走 MP 默认实现 = 翻转后静默软删。"
									+ "接物理删或显式 throw 都可以，但不能留空")
									.formatted(javaClass.getName(), method,
											EntityPredicates.physicalDeleteEntityOf(javaClass).orElse("?"))));
				}
			}
		};
	}

	private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> haveDecidedDeleteSemantics() {
		return new ArchCondition<>("declare @TableLogic or be registered as physical-delete") {
			@Override
			public void check(com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
				EntityPredicates.DelFlagState state = EntityPredicates.delFlagState(javaClass);
				String name = javaClass.getSimpleName();
				boolean registered = PhysicalDeleteRegistry.isPhysicalDelete(name)
						&& !PhysicalDeleteRegistry.ENTITIES.get(name).isBlank();
				boolean satisfied = switch (state) {
					case TABLE_LOGIC -> true;
					case PLAIN -> registered;
					// fail-closed：连 delFlag 字段都解析不到，说明继承链或字段名与假设不符，
					// 一律判违例。宁可误报也不能像 R1b 的 @Inside(value=false) 那样静默漏放。
					case UNRESOLVED -> false;
				};
				events.add(new SimpleConditionEvent(javaClass, satisfied,
						"%s 的删除语义未定：delFlag=%s，且未登记在 PhysicalDeleteRegistry"
								.formatted(javaClass.getName(), state)));
			}
		};
	}

	private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> bePhysicalOrReviewed() {
		return new ArchCondition<>("be physical-delete or reviewed for delete-then-reinsert") {
			@Override
			public void check(com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
				String name = javaClass.getSimpleName();
				boolean physical = PhysicalDeleteRegistry.isPhysicalDelete(name);
				boolean reviewed = SoftDeleteInputIdReviewed.isReviewed(name)
						&& !SoftDeleteInputIdReviewed.ENTITIES.get(name).isBlank();
				events.add(new SimpleConditionEvent(javaClass, physical || reviewed,
						("%s 是 IdType.INPUT 且走软删，但既未登记物理删、也没有「不会删+重插」的复核结论。"
								+ "请实际排查它的删除与插入调用点后登记，不要凭印象写理由")
								.formatted(javaClass.getName())));
			}
		};
	}

	private static ArchCondition<com.tngtech.archunit.core.domain.JavaClass> beRegisteredAsOutsideFlip() {
		return new ArchCondition<>("be registered in OutsideFlipEntities or declare its own @TableLogic") {
			@Override
			public void check(com.tngtech.archunit.core.domain.JavaClass javaClass, ConditionEvents events) {
				String name = javaClass.getSimpleName();
				boolean ownTableLogic = EntityPredicates.delFlagState(javaClass) == EntityPredicates.DelFlagState.TABLE_LOGIC;
				boolean registered = OutsideFlipEntities.isRegistered(name)
						&& !OutsideFlipEntities.ENTITIES.get(name).isBlank();
				events.add(new SimpleConditionEvent(javaClass, ownTableLogic || registered,
						"%s 有 del_flag 却不继承 BaseEntity：翻转对它不生效（仍是物理删），"
								.formatted(javaClass.getName()) + "必须登记进 OutsideFlipEntities 说明现状"));
			}
		};
	}

	private static final com.tngtech.archunit.base.DescribedPredicate<JavaMethod> SWALLOWS_EXCEPTION =
			new com.tngtech.archunit.base.DescribedPredicate<>("吞掉异常并返回失败响应") {
				@Override
				public boolean test(JavaMethod method) {
					return SwallowedExceptions.swallowsException(method);
				}
			};

	// ---------- conditions ----------

	private static ArchCondition<JavaMethod> beAnnotatedWithSysLogUnlessExempt() {
		return new ArchCondition<>("be annotated with @SysLog") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				if (ArchExemptions.exemptFromSysLog(method)) {
					return;
				}
				boolean satisfied = method.isAnnotatedWith(ArchPredicates.ANN_SYS_LOG);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 缺少 @SysLog".formatted(method.getFullName())));
			}
		};
	}

	private static ArchCondition<JavaMethod> declareAuthorizationUnlessExempt() {
		return new ArchCondition<>("declare authorization (@PreAuthorize or @ApiLogin)") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				if (ArchExemptions.exemptFromPreAuthorize(method)) {
					return;
				}
				// 两套体系任一即可：后台 @PreAuthorize 权限码 / C 端 @ApiLogin 第三方 session。
				// 类级 @ApiLogin 对全类方法生效，须一并检查。
				boolean satisfied = method.isAnnotatedWith(ArchPredicates.ANN_PRE_AUTHORIZE)
						|| method.isAnnotatedWith(ArchPredicates.ANN_API_LOGIN)
						|| method.getOwner().isAnnotatedWith(ArchPredicates.ANN_API_LOGIN);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 既无 @PreAuthorize 也无 @ApiLogin（无鉴权裸接口）".formatted(method.getFullName())));
			}
		};
	}

	private static ArchCondition<JavaMethod> declareMethodLevelApiLoginUnlessExempt() {
		return new ArchCondition<>("declare method-level @ApiLogin") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				if (ArchExemptions.exemptFromMethodLevelApiLogin(method)) {
					return;
				}
				// 刻意只看方法级：类级 @ApiLogin 在 ThirdSessionInterceptor 里不生效，
				// 认它就等于把这条规则判据放宽到与 R1b 相同，也就抓不到 R1b 抓不到的东西。
				boolean satisfied = method.isAnnotatedWith(ArchPredicates.ANN_API_LOGIN);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 缺少方法级 @ApiLogin（类级注解运行期不生效，等于无鉴权）"
								.formatted(method.getFullName())));
			}
		};
	}

	/**
	 * R1d 用<b>显式登记</b>而不是 {@code FreezingArchRule} baseline。
	 *
	 * <p>baseline 会把存量静默吸收进一个大文件，没人再逐条看；而这一类只有个位数，
	 * 每一条都值得有人念出它的名字和理由——这正是「用 GET 表达写语义」能活这么久的原因。
	 */
	private static ArchCondition<JavaMethod> beRegisteredReadSideWrite() {
		return new ArchCondition<>("be a registered read-side write") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				boolean satisfied = ArchExemptions.isRegisteredReadSideWrite(method);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 是 GET 端点却直接写库。要么改成 POST/PUT（并按 R1c 挂方法级 @ApiLogin），"
								.formatted(method.getFullName())
								+ "要么在 ArchExemptions.READ_SIDE_WRITE_METHODS 里登记并写清为什么必须是 GET"));
			}
		};
	}

	private static ArchCondition<JavaMethod> beTransactional() {
		return new ArchCondition<>("be annotated with @Transactional") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				boolean satisfied = ArchPredicates.isTransactional(method);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 跨表写但无 @Transactional".formatted(method.getFullName())));
			}
		};
	}

	private static ArchCondition<JavaMethod> recordErrorLog() {
		return new ArchCondition<>("call ErrorLogRecorder.record") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				// 谓词已经筛出「未调用 record」的方法，所以走到这里一律是违例
				events.add(SimpleConditionEvent.violated(method,
						"%s 吞掉异常后返回失败响应，但未调用 ErrorLogRecorder.record".formatted(method.getFullName())));
			}
		};
	}

	private static ArchCondition<JavaMethod> returnBatchResult() {
		return new ArchCondition<>("return BatchResult") {
			@Override
			public void check(JavaMethod method, ConditionEvents events) {
				boolean satisfied = BatchEndpoints.returnsBatchResult(method);
				events.add(new SimpleConditionEvent(method, satisfied,
						"%s 是批量端点但返回类型不含 BatchResult".formatted(method.getFullName())));
			}
		};
	}
}
