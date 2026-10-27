package com.zhiflow.common.data.mybatis;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * {@code onConfigChanged} 通知所有登记的缓存作废。
 */
@DisplayName("SQL 改写配置刷新通知")
class SqlRewriteConfigRefreshTest {

	@Test
	@DisplayName("onConfigChanged：通知所有登记的缓存作废，每次刷新各通知一次")
	void onConfigChangedInvalidatesAllListeners() {
		SqlRewriteConfigRefresh refresh = new SqlRewriteConfigRefresh();
		AtomicInteger first = new AtomicInteger();
		AtomicInteger second = new AtomicInteger();
		refresh.addInvalidationListener(first::incrementAndGet);
		refresh.addInvalidationListener(second::incrementAndGet);

		refresh.onConfigChanged();
		assertEquals(1, first.get());
		assertEquals(1, second.get());

		refresh.onConfigChanged();
		assertEquals(2, first.get());
		assertEquals(2, second.get());
	}

}
