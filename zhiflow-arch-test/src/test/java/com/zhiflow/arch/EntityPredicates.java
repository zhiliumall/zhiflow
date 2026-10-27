package com.zhiflow.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaAnnotation;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaEnumConstant;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.domain.JavaModifier;

import java.util.Optional;

/**
 * 实体侧规则（R6/R7/R8）的判据。
 *
 * <p><b>一条总原则：只看实体自身的声明，绝不从调用点反推。</b>
 * 旧的 R3 就是调用点规则，它按被调方法名判定，看不见「方法名没变但 Service 内部
 * 已经从软删换成物理删」——S8 波次 2/C1 改的那几个实体在 R3 的数字上完全不体现。
 *
 * <p><b>第二条原则：解析失败一律 fail-closed 判违例。</b>
 * R1b 曾把「对外完全开放」的 {@code @Inside(value=false)} 端点当内部端点豁免掉，
 * 原因就是判据只看注解在不在、读不到值就当通过。这里反过来：读不到就算违例。
 */
final class EntityPredicates {

	private static final String BASE_ENTITY = "com.zhiflow.common.core.entity.base.BaseEntity";
	private static final String ANN_TABLE_LOGIC = "com.baomidou.mybatisplus.annotation.TableLogic";
	private static final String ANN_TABLE_ID = "com.baomidou.mybatisplus.annotation.TableId";
	private static final String ANN_TABLE_NAME = "com.baomidou.mybatisplus.annotation.TableName";
	private static final String DEL_FLAG = "delFlag";

	/** delFlag 字段的三种状态，{@link #UNRESOLVED} 用于 fail-closed */
	enum DelFlagState {
		/** 找到 delFlag 且带 @TableLogic —— 软删已生效 */
		TABLE_LOGIC,
		/** 找到 delFlag 但没有 @TableLogic —— 物理删 */
		PLAIN,
		/** 整条继承链上找不到 delFlag —— 判据与代码结构不符，按违例处理 */
		UNRESOLVED
	}

	private EntityPredicates() {
	}

	/** 继承 BaseEntity 的具体实体类（抽象类/接口/枚举本身不落表，排除） */
	static final DescribedPredicate<JavaClass> IS_CONCRETE_BASE_ENTITY =
			new DescribedPredicate<>("是继承 BaseEntity 的具体实体") {
				@Override
				public boolean test(JavaClass javaClass) {
					return isConcreteClass(javaClass)
							&& javaClass.isAssignableTo(BASE_ENTITY)
							&& !javaClass.getName().equals(BASE_ENTITY);
				}
			};

	/** 主键策略是 IdType.INPUT（主键即业务键） */
	static final DescribedPredicate<JavaClass> HAS_INPUT_ID =
			new DescribedPredicate<>("主键是 IdType.INPUT") {
				@Override
				public boolean test(JavaClass javaClass) {
					return hasInputId(javaClass);
				}
			};

	/** 自带 delFlag 字段 + @TableName，但不继承 BaseEntity —— 翻转射程之外 */
	static final DescribedPredicate<JavaClass> HAS_DEL_FLAG_OUTSIDE_BASE_ENTITY =
			new DescribedPredicate<>("有 del_flag 但不继承 BaseEntity") {
				@Override
				public boolean test(JavaClass javaClass) {
					return isConcreteClass(javaClass)
							&& !javaClass.isAssignableTo(BASE_ENTITY)
							&& javaClass.isAnnotatedWith(ANN_TABLE_NAME)
							&& findDelFlag(javaClass).isPresent();
				}
			};

	/**
	 * 沿继承链查 delFlag 并判断有没有 {@code @TableLogic}。
	 *
	 * <p>必须沿继承链走：翻转是加在 {@code BaseEntity} 上的，子类自己没有这个字段。
	 */
	static DelFlagState delFlagState(JavaClass javaClass) {
		Optional<JavaField> field = findDelFlag(javaClass);
		if (field.isEmpty()) {
			return DelFlagState.UNRESOLVED;
		}
		return field.get().isAnnotatedWith(ANN_TABLE_LOGIC) ? DelFlagState.TABLE_LOGIC : DelFlagState.PLAIN;
	}

	private static Optional<JavaField> findDelFlag(JavaClass javaClass) {
		for (JavaClass current = javaClass; current != null; current = current.getRawSuperclass().orElse(null)) {
			for (JavaField field : current.getFields()) {
				if (DEL_FLAG.equals(field.getName())) {
					return Optional.of(field);
				}
			}
		}
		return Optional.empty();
	}

	/**
	 * 沿继承链找 {@code @TableId} 字段，判断 {@code type} 是否为 {@code INPUT}。
	 *
	 * <p>fail-closed：注解存在但 {@code type} 读不出来时按 <b>INPUT 处理</b>（返回 true），
	 * 让它落进 R7 要求登记 —— 宁可多要一条复核结论，也不要漏掉一个主键即业务键的实体。
	 * 完全没有 {@code @TableId} 时返回 false：那说明主键不在本类声明，
	 * 由 R6 而非 R7 负责，不在这里制造噪声。
	 */
	private static boolean hasInputId(JavaClass javaClass) {
		for (JavaClass current = javaClass; current != null; current = current.getRawSuperclass().orElse(null)) {
			for (JavaField field : current.getFields()) {
				if (!field.isAnnotatedWith(ANN_TABLE_ID)) {
					continue;
				}
				JavaAnnotation<?> annotation = field.getAnnotationOfType(ANN_TABLE_ID);
				Optional<Object> type = annotation.get("type");
				if (type.isEmpty()) {
					// 未显式写 type：MP 的默认是 IdType.NONE（跟随全局配置），不是 INPUT
					return false;
				}
				if (type.get() instanceof JavaEnumConstant enumConstant) {
					return "INPUT".equals(enumConstant.name());
				}
				// 读到了但不是枚举常量 —— 判据与实际结构不符，fail-closed 当作 INPUT
				return true;
			}
		}
		return false;
	}

	/** MyBatis-Plus 的四个删除入口。少覆盖一个，那个入口翻转后就是静默软删 */
	static final java.util.List<String> DELETE_ENTRY_POINTS =
			java.util.List.of("removeById", "removeByIds", "removeBatchByIds", "remove");

	/**
	 * 是「物理删实体」的 ServiceImpl —— 即 {@code extends ServiceImpl<XxxMapper, 实体>}
	 * 且该实体登记在 {@link PhysicalDeleteRegistry}。
	 *
	 * <p>从<b>泛型参数</b>取实体类型，不从类名猜：{@code ChainCommissionRecordServiceImpl}
	 * 这类命名与实体同名纯属巧合，靠名字匹配迟早会错配。
	 */
	static final DescribedPredicate<JavaClass> IS_PHYSICAL_DELETE_SERVICE_IMPL =
			new DescribedPredicate<>("是物理删实体的 ServiceImpl") {
				@Override
				public boolean test(JavaClass javaClass) {
					return physicalDeleteEntityOf(javaClass).isPresent();
				}
			};

	/**
	 * 取该 ServiceImpl 管的实体简单名，且仅当它登记为物理删时返回。
	 *
	 * <p>fail-closed 的边界在这里：解析不出泛型参数就返回空 = 该类不被 R9 检查。
	 * 这看似「漏放」，但相反方向更糟 —— 把所有 ServiceImpl 都判进来会产生上百条噪声违例，
	 * 让规则被整体忽略。真正的兜底是 R6：实体侧一定会要求每个实体明确声明删除语义，
	 * 而 R9 只负责「已经决定物理删的，四个入口别留空」。
	 */
	static java.util.Optional<String> physicalDeleteEntityOf(JavaClass javaClass) {
		if (!javaClass.getSimpleName().endsWith("ServiceImpl")) {
			return java.util.Optional.empty();
		}
		return javaClass.getSuperclass()
				.filter(t -> t.toErasure().getName().endsWith(".ServiceImpl"))
				.filter(t -> t instanceof com.tngtech.archunit.core.domain.JavaParameterizedType)
				.map(t -> (com.tngtech.archunit.core.domain.JavaParameterizedType) t)
				.map(com.tngtech.archunit.core.domain.JavaParameterizedType::getActualTypeArguments)
				.filter(args -> args.size() == 2)
				.map(args -> args.get(1).toErasure().getSimpleName())
				.filter(PhysicalDeleteRegistry::isPhysicalDelete);
	}

	private static boolean isConcreteClass(JavaClass javaClass) {
		return !javaClass.isInterface()
				&& !javaClass.isEnum()
				&& !javaClass.getModifiers().contains(JavaModifier.ABSTRACT)
				&& !javaClass.isAnonymousClass()
				&& !javaClass.isMemberClass();
	}
}
