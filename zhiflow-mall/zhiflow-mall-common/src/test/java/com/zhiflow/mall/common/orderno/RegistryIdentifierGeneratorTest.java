package com.zhiflow.mall.common.orderno;

import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * MyBatis-Plus {@code ASSIGN_ID} 主键的机器位必须来自注册表槽位，而不是 MAC + PID。
 *
 * <p>MP 的位结构：{@code timestamp << 22 | dataCenterId << 17 | workerId << 12 | sequence}。
 */
class RegistryIdentifierGeneratorTest {

	private final OrderNoGeneratorAutoConfiguration configuration = new OrderNoGeneratorAutoConfiguration();

	@Test
	@DisplayName("主键机器位 = 槽位的 workerId + 平移 16 的 dataCenterId")
	void machineBitsComeFromRegistrySlot() {
		IdentifierGenerator generator = this.configuration.registryIdentifierGenerator(new FixedWorkerIdAssigner(3, 5));

		long id = generator.nextId(new Object()).longValue();

		assertEquals(3, (id >> 12) & 31, "workerId");
		assertEquals(21, (id >> 17) & 31, "dataCenterId = (5 + 16) % 32");
	}

	@Test
	@DisplayName("平移是一一映射：两个不同槽位的进程同毫秒发号也不撞")
	void distinctSlotsNeverCollide() {
		IdentifierGenerator a = this.configuration.registryIdentifierGenerator(new FixedWorkerIdAssigner(2, 0));
		IdentifierGenerator b = this.configuration.registryIdentifierGenerator(new FixedWorkerIdAssigner(3, 0));
		IdentifierGenerator wrap = this.configuration.registryIdentifierGenerator(new FixedWorkerIdAssigner(2, 16));

		Set<Long> seen = new HashSet<>();
		for (int i = 0; i < 2000; i++) {
			assertTrue(seen.add(a.nextId(null).longValue()));
			assertTrue(seen.add(b.nextId(null).longValue()));
			assertTrue(seen.add(wrap.nextId(null).longValue()));
		}
	}

}
