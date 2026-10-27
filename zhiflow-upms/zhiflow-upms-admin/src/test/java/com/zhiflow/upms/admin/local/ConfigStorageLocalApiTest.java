package com.zhiflow.upms.admin.local;

import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.admin.controller.SysConfigStorageController;
import com.zhiflow.upms.common.entity.SysConfigStorage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.context.request.RequestAttributes;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 契约本地实现的样板用例。
 *
 * <p>本地实现只有三件事要证明，而且**每一件错了都不会在编译期或日志里报出来**：
 * ① 真的把调用转给了 controller（而不是绕过它去调 service）；
 * ② 调用发生在「内部调用身份」里（否则 {@code @Inside} 端点 403）；
 * ③ 调用结束后身份立刻消失（否则外层 C 端请求顶着内部身份继续跑）。
 */
class ConfigStorageLocalApiTest {

	@AfterEach
	void clear() {
		RequestContextHolder.resetRequestAttributes();
	}

	@Test
	@DisplayName("把调用转给 controller，并原样返回它的结果")
	void delegatesToController() {
		R<SysConfigStorage> expected = R.ok(new SysConfigStorage());
		ConfigStorageLocalApi api = new ConfigStorageLocalApi(controllerReturning(expected, new AtomicReference<>()));

		assertSame(expected, api.getSysConfigStorage(SecurityConstants.FROM_IN));
	}

	@Test
	@DisplayName("⚠️ controller 执行期间必须看得到 from=Y —— 这是 @Inside 端点不 403 的唯一原因")
	void controllerRunsUnderInternalIdentity() {
		AtomicReference<String> seen = new AtomicReference<>("<未执行>");
		bindOuterCEndRequest();

		new ConfigStorageLocalApi(controllerReturning(R.ok(new SysConfigStorage()), seen))
				.getSysConfigStorage(SecurityConstants.FROM_IN);

		assertEquals(SecurityConstants.FROM_IN, seen.get(),
				"controller 执行时读到的 from 不是内部标识，@Inside 端点会 403");
	}

	@Test
	@DisplayName("调用结束后身份立刻还原 —— 正常与异常两条路径都要还原")
	void identityDoesNotLeak() {
		RequestAttributes outer = new ServletRequestAttributes(bindOuterCEndRequest());
		RequestContextHolder.setRequestAttributes(outer);
		AtomicReference<String> ignored = new AtomicReference<>();

		new ConfigStorageLocalApi(controllerReturning(R.ok(new SysConfigStorage()), ignored))
				.getSysConfigStorage(SecurityConstants.FROM_IN);
		assertSame(outer, RequestContextHolder.getRequestAttributes());
		assertNull(currentFromHeader(), "外层 C 端请求不该带上 from —— 带上就是内部身份泄漏");

		ConfigStorageLocalApi boom = new ConfigStorageLocalApi(new SysConfigStorageController(null) {
			@Override
			public R getInside() {
				throw new IllegalStateException("boom");
			}
		});
		assertThrows(IllegalStateException.class, () -> boom.getSysConfigStorage(SecurityConstants.FROM_IN));
		assertSame(outer, RequestContextHolder.getRequestAttributes(), "异常路径也必须还原");
	}

	// ------------------------------------------------------------ 夹具

	/** controller 桩：记录它执行时看到的 from 头。 */
	private static SysConfigStorageController controllerReturning(R<SysConfigStorage> result,
			AtomicReference<String> seenFrom) {
		return new SysConfigStorageController(null) {
			@Override
			public R getInside() {
				seenFrom.set(currentFromHeader());
				return result;
			}
		};
	}

	private static String currentFromHeader() {
		RequestAttributes attributes = RequestContextHolder.getRequestAttributes();
		return attributes instanceof ServletRequestAttributes servlet
				? servlet.getRequest().getHeader(SecurityConstants.FROM) : null;
	}

	/** 外层 C 端请求：网关已把 from 剥掉，所以刻意不加。 */
	private static MockHttpServletRequest bindOuterCEndRequest() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/mallapi/order/page");
		RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(request));
		return request;
	}
}
