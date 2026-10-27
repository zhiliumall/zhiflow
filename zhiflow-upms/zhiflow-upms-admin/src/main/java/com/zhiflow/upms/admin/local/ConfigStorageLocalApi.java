package com.zhiflow.upms.admin.local;

import com.zhiflow.api.upms.ConfigStorageApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.upms.admin.controller.SysConfigStorageController;
import com.zhiflow.upms.common.dto.StorageCredentials;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ConfigStorageApi} 的**进程内实现**。本仓第一个，样板。
 *
 * <h2>它在什么时候生效</h2>
 * <ul>
 * <li><b>今天的集群形态</b>：本类在 upms-admin 里，而 upms-admin <b>自己也注册了一个指向自己的
 *     {@code ConfigStorageApi} HTTP 客户端</b>（全仓这样的自调有 26 处）。{@code @Primary}
 *     让注入点拿到本类 ⇒ <b>当场省掉一次 HTTP 自环</b>（序列化 + 网络 + 一整个请求周期）。</li>
 * <li><b>阶段 3 单体形态</b>：portal/support 装在同一个进程时，调用方注入到的也是本类。</li>
 * <li><b>跨进程时</b>：调用方那个进程里没有 upms-admin 的包、扫不到本类，
 *     注入到的仍是 HTTP 代理 —— 装配由 profile 的组件扫描范围决定，不需要额外开关。</li>
 * </ul>
 *
 * <h2>两处不能省的写法</h2>
 * <ol>
 * <li>{@code @Transactional(REQUIRES_NEW)}：HTTP 调用天然是**独立事务**（跨进程，调用方的事务
 *     传不过去）。装进同一个进程后如果不显式隔离，被调方就会**加入调用方的事务** ——
 *     调用方回滚会把被调方一起回滚掉，而今天不会。这是最容易被漏掉、且只在出错路径上才暴露的
 *     语义变化。<br>
 *     ⚠️ 代价要记住：{@code REQUIRES_NEW} 会挂起外层事务并**再占一条连接**，
 *     </li>
 * <li>{@link InternalCallContext#call}：{@code @Inside} 端点靠请求头 {@code from} 判内外，
 *     而进程内直调时安全切面读到的是<b>外层那个 C 端请求</b>（它的 {@code from} 已被网关剥掉）
 *     ⇒ 不包这一层就是 403。</li>
 * </ol>
 *
 * <h2>为什么调 controller 而不是 service</h2>
 * <p>controller 那一层不只是转发：它决定 {@code R<>} 包装、参数校验、{@code @SysLog} 留痕、
 * {@code @Inside} 鉴权。绕过它去调 service，等于对 124 个契约方法逐个人工确认
 * 「controller 里没有别的逻辑」—— 那是最容易静默改语义的做法。
 */
@Primary
@Component
public class ConfigStorageLocalApi implements ConfigStorageApi {

	private final SysConfigStorageController controller;

	/**
	 * ⚠️ 显式构造器 + {@code @Lazy}，两者都不能省。
	 *
	 * <p>controller 会牵出 service → mapper → {@code sqlSessionFactory} 一整条链。本类是
	 * {@code @Component}，**急切注入会把这条链提前到容器早期**，引爆本仓<b>既有的</b>循环引用 ——
	 * 2026-08-16 实测：12 个本地实现一起上线后 upms 起不来，
	 * {@code BeanCurrentlyInCreationException: 'sqlSessionFactory'}，流水线健康检查判死自动回滚。
	 * 单测直接 {@code new} 所以完全测不到这一类。
	 *
	 * <p>不用 {@code @RequiredArgsConstructor}：本仓没配 Lombok 的 {@code copyableAnnotations}，
	 * 写在字段上的 {@code @Lazy} 不会被复制到生成的构造器参数上，等于没写。
	 */
	public ConfigStorageLocalApi(@Lazy SysConfigStorageController controller) {
		this.controller = controller;
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<StorageCredentials> getSysConfigStorage(String from) {
		return InternalCallContext.call(() -> this.controller.getInside());
	}
}
