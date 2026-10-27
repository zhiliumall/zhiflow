package com.zhiflow.common.test;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 无容器环境下使用 MyBatis-Plus Wrapper 的支持类。
 *
 * <p><b>解决什么问题</b>：{@code LambdaQueryWrapper} 生成 SQL 片段要查 MyBatis-Plus 的
 * TableInfo 缓存，而那份缓存平时由 {@code SqlSessionFactory} 启动时填充。纯单测没有容器，
 * 于是 {@code getSqlSegment()} 直接抛
 * {@code MybatisPlusException: can not find lambda cache for this entity}。
 *
 * <p>此前每个要断言 Wrapper 的测试都得自己抄一遍注册代码（S5 的
 * {@code EntityImpactServiceImplTest} 一次注册了 11 个实体）。本类把它收成一行。
 *
 * <h2>用法</h2>
 * <pre>{@code
 * @BeforeAll
 * static void setUp() {
 *     MyBatisPlusTestSupport.register(CouponUser.class, GoodsSpu.class);
 * }
 * }</pre>
 */
public final class MyBatisPlusTestSupport {

	/**
	 * {@link MapperBuilderAssistant} 非线程安全，且 TableInfo 缓存是 JVM 级静态的。
	 * surefire 默认单 JVM 跑多个测试类，因此这里必须同时保证幂等与串行。
	 */
	private static final Object LOCK = new Object();

	private static final Set<Class<?>> REGISTERED = ConcurrentHashMap.newKeySet();

	private static MapperBuilderAssistant assistant;

	private MyBatisPlusTestSupport() {
	}

	/**
	 * 把实体注册进 TableInfo 缓存，使其 Lambda Wrapper 可用。
	 *
	 * <p>幂等：同一实体重复注册无副作用，多个测试类各自调用互不干扰。
	 */
	public static void register(Class<?>... entities) {
		synchronized (LOCK) {
			if (assistant == null) {
				assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
			}
			for (Class<?> entity : entities) {
				if (REGISTERED.add(entity)) {
					TableInfoHelper.initTableInfo(assistant, entity);
				}
			}
		}
	}

	/**
	 * 取 Wrapper 的 WHERE 片段，如 {@code (coupon_id = #{...} AND del_flag = #{...})}。
	 *
	 * <p>用来断言「这个查询有没有带上某个条件列」。注意它只证明列名出现过，
	 * <b>证明不了运算符对不对</b>（{@code eq} 写成 {@code ne} 同样含该列名）。
	 * 要证明语义，得靠真库测试。
	 */
	public static String sqlSegmentOf(Object wrapper) {
		return typed(wrapper).getSqlSegment();
	}

	/**
	 * 取 Wrapper 的绑定参数表。
	 *
	 * <p><b>这里是本类存在的第二个理由</b>：{@code eq()} 存进去的是一个 lambda，
	 * 参数要等 SQL 片段真正生成时才落进 {@code paramNameValuePairs}。
	 * 直接读会拿到<b>空 map</b>——不报错，只是所有断言静默失效
	 * （S5 实测：靠绑定值区分两次调用的桩，两次都判 false，返回值全串了，花了两轮才定位）。
	 * 所以这里先 materialize 再读。
	 */
	public static Map<String, Object> boundValuesOf(Object wrapper) {
		AbstractWrapper<?, ?, ?> typed = typed(wrapper);
		typed.getSqlSegment();
		return typed.getParamNameValuePairs();
	}

	/** 绑定参数里是否含指定值——用来区分同一个 Mapper 的多次不同调用 */
	public static boolean boundValuesContain(Object wrapper, Object value) {
		return boundValuesOf(wrapper).containsValue(value);
	}

	private static AbstractWrapper<?, ?, ?> typed(Object wrapper) {
		if (wrapper instanceof AbstractWrapper<?, ?, ?> typed) {
			return typed;
		}
		throw new IllegalArgumentException(
				"期望 AbstractWrapper（LambdaQueryWrapper 等），实际是 "
						+ (wrapper == null ? "null" : wrapper.getClass().getName()));
	}
}
