package com.zhiflow.common.test;

import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * 纯 Mockito 单测基类（无 DB / 无 Redis / 无 Spring 容器）。
 *
 * <p>只收敛 {@code @ExtendWith(MockitoExtension.class)} 这一行样板。
 *
 * <p><b>刻意保留 Mockito 默认的 STRICT_STUBS，不在基类里放宽成 LENIENT。</b>
 * 「打了桩却没被调用」绝大多数时候意味着这个测试没在测你以为的东西——比如方法名写错、
 * 分支根本没走到。把它降级成静默通过，等于把一类真 bug 变成看不见。
 *
 * <p>确实需要放宽的测试自己加：
 * <pre>{@code
 * @MockitoSettings(strictness = Strictness.LENIENT)
 * class FooTest extends BaseMockitoUnitTest { ... }
 * }</pre>
 *
 * <p>要断言 MyBatis-Plus 的 Wrapper，配合 {@link MyBatisPlusTestSupport} 使用。
 */
@ExtendWith(MockitoExtension.class)
public abstract class BaseMockitoUnitTest {

}
