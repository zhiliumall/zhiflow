package com.zhiflow.common.test;

import uk.co.jemos.podam.api.PodamFactory;
import uk.co.jemos.podam.api.PodamFactoryImpl;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 随机测试对象生成（对标 yudao {@code RandomUtils}，同样基于 podam）。
 *
 * <p><b>用途</b>：造测试数据时不必逐字段 setter。关心哪几个字段就设哪几个，
 * 其余由 podam 填随机值——这样测试代码里出现的字段就是**这个测试真正关心的字段**，
 * 噪音字段不会混进来误导读者。
 *
 * <pre>{@code
 * CouponUser used = randomPojo(CouponUser.class, u -> {
 *     u.setCouponId("c-1");
 *     u.setStatus("1");        // 只有这两个字段与断言有关
 * });
 * }</pre>
 *
 * <p><b>已知边界</b>：podam 靠反射递归填充，遇到自引用实体、
 * {@code cn.hutool.json.JSONObject} 这类结构复杂的字段可能很慢或填出无意义的值。
 * 碰到就别硬用 {@code randomPojo}，直接 {@code new} 出来手工设几个字段更省事。
 */
public final class RandomUtils {

	private static final PodamFactory FACTORY = new PodamFactoryImpl();

	private RandomUtils() {
	}

	/** 生成一个各字段填了随机值的对象 */
	public static <T> T randomPojo(Class<T> clazz) {
		return FACTORY.manufacturePojo(clazz);
	}

	/** 生成随机对象后，用 customizer 覆盖测试真正关心的字段 */
	public static <T> T randomPojo(Class<T> clazz, Consumer<T> customizer) {
		T pojo = randomPojo(clazz);
		customizer.accept(pojo);
		return pojo;
	}

	/** 生成 count 个随机对象；customizer 附带序号，便于造出可区分的数据 */
	public static <T> List<T> randomPojoList(Class<T> clazz, int count, IndexedConsumer<T> customizer) {
		List<T> list = new ArrayList<>(count);
		for (int i = 0; i < count; i++) {
			T pojo = randomPojo(clazz);
			if (customizer != null) {
				customizer.accept(pojo, i);
			}
			list.add(pojo);
		}
		return list;
	}

	public static <T> List<T> randomPojoList(Class<T> clazz, int count) {
		return randomPojoList(clazz, count, null);
	}

	// 标量走 manufacturePojo 而不是 strategy.getTypeValue —— 后者要求传入非 null 的
	// AttributeMetadata（它是给字段级填充用的内部 API），脱离字段上下文直接调会抛
	// IllegalArgumentException: The attribute metadata inside the wrapper cannot be null。

	public static String randomString() {
		return FACTORY.manufacturePojo(String.class);
	}

	public static Long randomLong() {
		return FACTORY.manufacturePojo(Long.class);
	}

	public static Integer randomInt() {
		return FACTORY.manufacturePojo(Integer.class);
	}

	/** 闭区间 [min, max] 内的随机整数 */
	public static int randomInt(int min, int max) {
		if (min > max) {
			throw new IllegalArgumentException("min(%d) 不能大于 max(%d)".formatted(min, max));
		}
		return java.util.concurrent.ThreadLocalRandom.current().nextInt(min, max + 1);
	}

	@FunctionalInterface
	public interface IndexedConsumer<T> {

		void accept(T pojo, int index);
	}
}
