package com.zhiflow.common.test;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.opentest4j.AssertionFailedError;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 测试基建自测。
 *
 * <p>基建出错的后果比业务代码出错更隐蔽——它会让一批测试<b>静默地测不到东西</b>而不是变红。
 * 所以这三个工具类自己必须有测试。
 */
class TestInfraSelfTest {

	@BeforeAll
	static void registerEntity() {
		MyBatisPlusTestSupport.register(TestEntity.class);
	}

	@Nested
	@DisplayName("MyBatisPlusTestSupport")
	class MyBatisPlusSupport {

		/** 注册后 Lambda Wrapper 才能生成 SQL；不注册会抛 can not find lambda cache */
		@Test
		void register_makesLambdaWrapperUsable() {
			String sql = MyBatisPlusTestSupport.sqlSegmentOf(Wrappers.<TestEntity>lambdaQuery()
					.eq(TestEntity::getName, "n-1")
					.eq(TestEntity::getDelFlag, "0"));

			assertTrue(sql.contains("name"), sql);
			// 父类字段也要能解析——业务实体的 del_flag 就在父类上
			assertTrue(sql.contains("del_flag"), sql);
		}

		/** 幂等：surefire 单 JVM 跑多个测试类，重复注册不得报错 */
		@Test
		void register_isIdempotent() {
			MyBatisPlusTestSupport.register(TestEntity.class);
			MyBatisPlusTestSupport.register(TestEntity.class, TestEntity.class);

			assertNotNull(MyBatisPlusTestSupport.sqlSegmentOf(
					Wrappers.<TestEntity>lambdaQuery().eq(TestEntity::getId, "x")));
		}

		/**
		 * 本类存在的核心理由：绑定参数是懒生成的。
		 * 这条测试锁死「boundValuesOf 内部必须先 materialize」——去掉那行 getSqlSegment() 它就红。
		 */
		@Test
		void boundValues_areMaterializedNotEmpty() {
			var wrapper = Wrappers.<TestEntity>lambdaQuery()
					.eq(TestEntity::getName, "n-1")
					.eq(TestEntity::getStatus, "1");

			assertEquals(2, MyBatisPlusTestSupport.boundValuesOf(wrapper).size(),
					"绑定参数为空说明没有 materialize，靠绑定值做的断言会全部静默失效");
			assertTrue(MyBatisPlusTestSupport.boundValuesContain(wrapper, "1"));
			assertFalse(MyBatisPlusTestSupport.boundValuesContain(wrapper, "不存在的值"));
		}

		@Test
		void nonWrapper_failsLoudly() {
			assertThrows(IllegalArgumentException.class,
					() -> MyBatisPlusTestSupport.sqlSegmentOf("我不是 Wrapper"));
		}
	}

	@Nested
	@DisplayName("RandomUtils（podam 在 JDK 25 上的可用性）")
	class Random {

		/** podam 重度反射，JDK 25 能否跑通是本任务的已知风险点，这条就是那个验证 */
		@Test
		void randomPojo_fillsAllFieldsIncludingInherited() {
			TestEntity pojo = RandomUtils.randomPojo(TestEntity.class);

			assertNotNull(pojo.getId());
			assertNotNull(pojo.getName());
			assertNotNull(pojo.getSort());
			assertNotNull(pojo.getAmount());
			assertNotNull(pojo.getEffectiveTime(), "LocalDateTime 字段");
			assertNotNull(pojo.getLabels(), "String[] 字段");
			assertNotNull(pojo.getDelFlag(), "父类字段也要填");
		}

		@Test
		void randomPojo_customizerOverridesWhatTheTestCaresAbout() {
			TestEntity pojo = RandomUtils.randomPojo(TestEntity.class, e -> {
				e.setName("固定名字");
				e.setDelFlag("0");
			});

			assertEquals("固定名字", pojo.getName());
			assertEquals("0", pojo.getDelFlag());
			assertNotNull(pojo.getStatus(), "没被 customizer 覆盖的字段仍是随机值");
		}

		@Test
		void randomPojoList_indexedCustomizerMakesRowsDistinguishable() {
			List<TestEntity> list = RandomUtils.randomPojoList(TestEntity.class, 3,
					(e, i) -> e.setName("row-" + i));

			assertEquals(3, list.size());
			assertEquals(List.of("row-0", "row-1", "row-2"),
					list.stream().map(TestEntity::getName).toList());
		}

		@Test
		void randomScalars_areNotNull() {
			assertNotNull(RandomUtils.randomString());
			assertNotNull(RandomUtils.randomLong());
			assertNotNull(RandomUtils.randomInt());
		}

		@Test
		void randomInt_staysInClosedRange() {
			for (int i = 0; i < 50; i++) {
				int value = RandomUtils.randomInt(3, 5);
				assertTrue(value >= 3 && value <= 5, "越界：" + value);
			}
			assertEquals(7, RandomUtils.randomInt(7, 7), "单点区间");
			assertThrows(IllegalArgumentException.class, () -> RandomUtils.randomInt(5, 3));
		}
	}

	@Nested
	@DisplayName("AssertUtils")
	class Assertions {

		@Test
		void identicalPojos_pass() {
			TestEntity a = fixed();
			TestEntity b = fixed();

			AssertUtils.assertPojoEquals(a, b);
		}

		@Test
		void mismatch_failsAndNamesEveryOffendingField() {
			TestEntity a = fixed();
			TestEntity b = fixed();
			b.setName("别的名字");
			b.setSort(999);

			AssertionFailedError error = assertThrows(AssertionFailedError.class,
					() -> AssertUtils.assertPojoEquals(a, b));

			// 一次报全部，而不是撞到第一个就停
			assertTrue(error.getMessage().contains("name"), error.getMessage());
			assertTrue(error.getMessage().contains("sort"), error.getMessage());
			assertTrue(error.getMessage().contains("2 个字段不一致"), error.getMessage());
		}

		@Test
		void ignoredFields_areSkipped() {
			TestEntity a = fixed();
			TestEntity b = fixed();
			b.setId("别的 id");
			b.setCreateTime(b.getCreateTime().plusDays(1));

			AssertUtils.assertPojoEquals(a, b, "id", "createTime");
		}

		/** String[] 要按内容比——Objects.equals 比的是引用，会误报不一致 */
		@Test
		void arrayFields_compareByContentNotReference() {
			TestEntity a = fixed();
			TestEntity b = fixed();
			b.setLabels(new String[] { "x", "y" });

			AssertUtils.assertPojoEquals(a, b);
		}

		@Test
		void inheritedFields_areCompared() {
			TestEntity a = fixed();
			TestEntity b = fixed();
			b.setDelFlag("1");

			AssertionFailedError error = assertThrows(AssertionFailedError.class,
					() -> AssertUtils.assertPojoEquals(a, b));

			assertTrue(error.getMessage().contains("delFlag"), error.getMessage());
		}

		private TestEntity fixed() {
			TestEntity e = new TestEntity();
			e.setId("id-1");
			e.setName("名字");
			e.setStatus("0");
			e.setSort(1);
			e.setLabels(new String[] { "x", "y" });
			e.setDelFlag("0");
			e.setCreateTime(java.time.LocalDateTime.of(2026, 7, 25, 12, 0));
			return e;
		}
	}
}
