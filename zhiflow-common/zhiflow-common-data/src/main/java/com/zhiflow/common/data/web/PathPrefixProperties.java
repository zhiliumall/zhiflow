package com.zhiflow.common.data.web;

import com.zhiflow.common.core.constant.PathNamespace;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * [P2.3-A] 服务内部路径前缀。
 *
 * <p><b>要解决的问题</b>：单体把多个模块的 controller 装进同一个进程，
 * 部分模块之间有大量同路径映射（`/order/**`、`/goodsspu/**`……）。
 * 同一个 {@code RequestMappingHandlerMapping} 里两个相同的映射会直接抛
 * {@code Ambiguous mapping}，必须给各模块的 controller 分别加一段内部路径前缀隔开。
 *
 * <h2>{@code legacy-compat} 为什么必须有</h2>
 * <p>契约调用<b>直连实例</b>：{@code HttpInterfaceClientFactory} 用
 * {@code http://<服务名>/<path>} 经 LoadBalancer 直连，不经过任何转发层。所以服务一旦加了前缀，
 * 所有调用方的 {@code @HttpExchange} 路径都要跟着改、并且必须同时上线——分批发布时，
 * 只要有先后顺序，中间就会有一段「调用方发老路径、服务端只认新路径」的 404 窗口，
 * 落在资金链路上更不可接受。
 *
 * <p>所以开着 {@code legacy-compat} 时，服务<b>同时认</b>老路径与新路径
 * （见 {@link LegacyPathPrefixFilter}）：加前缀这一次发版零窗口，
 * 调用方随后各自按自己的节奏迁移，全部迁完再关掉这个开关做收尾。
 * 这是 expand / migrate / contract，不是「先破坏再修好」。
 */
@Getter
@Setter
@ConfigurationProperties(prefix = "zhiflow.path-prefix")
public class PathPrefixProperties {

	/**
	 * <b>多命名空间</b>：单体（形态一 {@code all}）专用，把 N 个服务的 controller
	 * 各自挂各自的前缀。
	 *
	 * <h2>为什么必须有这个</h2>
	 * <p>P2.3 让「外部路径」全局唯一，靠的是**运行期给 controller 加前缀**。
	 * 但 {@link #value} / {@link #basePackage} 是<b>一个进程一份</b> —— 8 个服务装进同一个
	 * 进程后只能配一个前缀，于是 {@code /token/page}（auth 与 upms 都有）、
	 * {@code /order/**}（mall-api 与 mall-admin 都有）这些在
	 * {@code RequestMappingHandlerMapping} 里直接 {@code Ambiguous mapping}，进程起不来。
	 * 2026-08-16 单体冒烟实测到的就是 upms 的 {@code TokenController}。
	 *
	 * <h2>⚠️ 与多进程形态的关系：留空即恒等变换</h2>
	 * <p>本列表<b>只有单体会配</b>。留空（默认）时 {@link PathPrefixAutoConfiguration}
	 * 走的还是 {@link #value} 那条老路径，一行代码都不经过新逻辑 ——
	 * 「多进程零变化」由「新代码根本不执行」保证，而不是靠逻辑等价论证。
	 */
	private List<PathNamespace> namespaces = new ArrayList<>();

	/** 内部前缀，如 {@code /pay}。留空 = 本服务不做前缀改造（默认）。 */
	private String value = "";

	/**
	 * 只给这个包前缀下的 controller 加前缀。
	 *
	 * <p><b>必须按包名限定</b>：全局加会把 actuator、springdoc 这些不属于本服务的
	 * handler 也一起改掉，健康检查与监控随之全挂。
	 */
	private String basePackage = "";

	/**
	 * 是否同时接受未加前缀的老路径（默认 {@code true}）。
	 *
	 * <p>迁移期开着；等该服务的全部调用方与网关路由都改完，再置 {@code false} 收尾，
	 * 那一步才是真正「只剩一种路径」。**别急着关**——关早了就等于把上面说的
	 * 那个 404 窗口重新制造一遍。
	 */
	private boolean legacyCompat = true;

	public boolean isEnabled() {
		return this.value != null && !this.value.isBlank();
	}

	/**
	 * 是否是单体的多命名空间形态。
	 *
	 * <p>判据是「列表里有<b>至少一个带前缀的条目</b>」而不是「列表非空」：
	 * 配了一个空壳条目时两者会给出不同答案，而这一类不一致正是
	 * {@code @ConditionalOnProperty} 判「有没有这个键」踩过的坑
	 * （P2.3-B 那次两条 any-request 链导致启动失败）。
	 */
	public boolean isMultiNamespace() {
		return this.namespaces != null && this.namespaces.stream().anyMatch(PathNamespace::hasValue);
	}
}
