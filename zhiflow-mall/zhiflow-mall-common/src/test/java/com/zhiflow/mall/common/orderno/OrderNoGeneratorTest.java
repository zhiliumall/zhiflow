package com.zhiflow.mall.common.orderno;

import cn.hutool.core.util.IdUtil;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * T1 收口的对拍验收：证明 {@link OrderNoGenerator} 与收口前的
 * {@code IdUtil.getSnowflake(0,0).nextIdStr()} 是**同一个生成器**，输出逐字节等价。
 */
class OrderNoGeneratorTest {

	private static final int ROUNDS = 100;

	private static OrderNoGenerator generator() {
		return new OrderNoGenerator(new FixedWorkerIdAssigner(0L, 0L), new OrderNoMetrics(null), new NoOpOrderNoFailureSink());
	}

	/**
	 * 核心对拍。
	 *
	 * <p>{@code System.currentTimeMillis()} 冻结不了，「各生成 100 个再逐号 equals」不可行，
	 * 改用等价的充要判据：同一个 {@code Snowflake} 实例的 {@code nextId()} 带
	 * {@code synchronized} 且严格单调递增。**交替**调用新入口与旧写法各 100 次，
	 * 若两者确实共用一个实例，这 200 个号必然全局唯一且严格递增；
	 * 若是两个独立实例（例如把 {@code IdUtil.getSnowflake} 误写成 {@code new Snowflake}），
	 * 各自从 sequence=0 起步，交替序列必然出现重复或逆序。
	 *
	 * <p>这比「各生成 100 个再比对」更强——后者在两个独立实例下反而可能因时间戳前进
	 * 而"看起来没问题"。
	 */
	@Test
	void newEntryPointSharesTheVerySameSnowflakeAsLegacyCallSites() {
		OrderNoGenerator generator = generator();
		List<BigInteger> sequence = new ArrayList<>(ROUNDS * 2);
		Set<String> distinct = new HashSet<>();

		for (int i = 0; i < ROUNDS; i++) {
			String viaNewEntry = generator.next();
			String viaLegacy = IdUtil.getSnowflake(0, 0).nextIdStr();
			sequence.add(new BigInteger(viaNewEntry));
			sequence.add(new BigInteger(viaLegacy));
			distinct.add(viaNewEntry);
			distinct.add(viaLegacy);
		}

		assertEquals(ROUNDS * 2, distinct.size(),
				"新旧入口交替生成出现重复号 —— 说明二者不是同一个 Snowflake 实例");
		for (int i = 1; i < sequence.size(); i++) {
			assertTrue(sequence.get(i).compareTo(sequence.get(i - 1)) > 0,
					"第 " + i + " 个号未严格递增 —— 说明二者不是同一个 Snowflake 实例");
		}
	}

	/** 更直接的一条：底层实例的引用相等。 */
	@Test
	void delegatesToHutoolSingletonNotANewInstance() throws Exception {
		assertSame(IdUtil.getSnowflake(0, 0), IdUtil.getSnowflake(0, 0),
				"前提：Hutool 按「类+参数」缓存单例");

		Field field = OrderNoGenerator.class.getDeclaredField("snowflake");
		field.setAccessible(true);
		assertSame(IdUtil.getSnowflake(0, 0), field.get(generator()),
				"OrderNoGenerator 必须复用 Hutool Singleton，不能 new Snowflake");
	}

	/** 格式不变：19 位纯数字。 */
	@Test
	void producesNineteenDigitNumericString() {
		OrderNoGenerator generator = generator();
		for (int i = 0; i < ROUNDS; i++) {
			String orderNo = generator.next();
			assertEquals(19, orderNo.length(), "订单号长度变了：" + orderNo);
			assertTrue(orderNo.matches("\\d{19}"), "订单号非纯数字：" + orderNo);
		}
	}

	/**
	 * 位结构不变：过渡期机器位仍是 (0,0)，即中间 10 位恒为 0。
	 *
	 * <p>位结构 = 时间戳 &lt;&lt; 22 | dataCenterId(5) &lt;&lt; 17 | workerId(5) &lt;&lt; 12 | sequence(12)
	 */
	@Test
	void machineBitsRemainZeroDuringTransition() {
		OrderNoGenerator generator = generator();
		for (int i = 0; i < ROUNDS; i++) {
			long id = Long.parseLong(generator.next());
			assertEquals(0L, (id >> 17) & 0x1F, "dataCenterId 应为 0（T2 之前不变）");
			assertEquals(0L, (id >> 12) & 0x1F, "workerId 应为 0（T2 之前不变）");
		}
	}

	/** 机器位来自构造器注入的 assigner —— T2 直接换实现即可，不必改本类。 */
	@Test
	void machineBitsComeFromInjectedAssigner() {
		OrderNoGenerator custom = new OrderNoGenerator(new FixedWorkerIdAssigner(7L, 3L), new OrderNoMetrics(null), new NoOpOrderNoFailureSink());

		assertEquals(7L, custom.getWorkerId());
		assertEquals(3L, custom.getDataCenterId());

		long id = Long.parseLong(custom.next());
		assertEquals(3L, (id >> 17) & 0x1F, "dataCenterId 应落进第 17-21 位");
		assertEquals(7L, (id >> 12) & 0x1F, "workerId 应落进第 12-16 位");
	}
}
