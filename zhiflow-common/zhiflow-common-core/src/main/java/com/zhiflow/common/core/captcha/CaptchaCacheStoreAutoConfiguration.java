package com.zhiflow.common.core.captcha;

import cloud.tianai.captcha.cache.CacheStore;
import cloud.tianai.captcha.spring.autoconfiguration.CacheStoreAutoConfiguration;
import cloud.tianai.captcha.spring.store.impl.RedisCacheStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.context.annotation.Bean;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 把滑块验证码的缓存存储**显式钉死成 Redis**，不再依赖自动配置的先后顺序。
 *
 * <h2>为什么需要这个类</h2>
 *
 * tianai-captcha 的 {@link CacheStoreAutoConfiguration} 里有两个嵌套配置：
 * <ul>
 *   <li>{@code RedisCacheStoreConfiguration}：{@code @ConditionalOnBean(StringRedisTemplate)}，优先级 1</li>
 *   <li>{@code LocalCacheStoreConfiguration}：兜底的 <b>JVM 内存</b>实现，优先级 2</li>
 * </ul>
 *
 * 它靠 {@code @AutoConfigureAfter(org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration.class)}
 * 来保证「Redis 的 bean 先注册好，再来判 {@code @ConditionalOnBean}」。
 * <b>那个类在 Spring Boot 4 里已经挪窝了</b>（现为
 * {@code org.springframework.boot.data.redis.autoconfigure.RedisAutoConfiguration}），
 * 于是这条注解读不出来 —— 启动日志里那行
 * {@code Failed to introspect meta-annotation @AutoConfigureAfter ... TypeNotPresentException} 就是它。
 * 注解读不出来 ⇒ <b>顺序保证没了</b>，判 {@code @ConditionalOnBean} 时 Redis 的 bean 在不在，变成了运气。
 *
 * <h2>赌输了会怎样</h2>
 *
 * 退化成 JVM 内存实现。而验证码是<b>跨进程</b>用的：
 * <b>mall-api 生成</b>（{@code /mallapi/captcha/generate}）、<b>网关校验</b>
 * （{@code ValidateCodeGatewayFilter}）。两个 JVM 各存各的内存 ⇒ 网关永远查不到 mall-api 生成的那条
 * ⇒ <b>全站登录被卡死</b>，而且一行报错都没有。
 *
 * <h2>做法</h2>
 *
 * 本配置声明 {@code before = CacheStoreAutoConfiguration.class}，先把 {@link CacheStore} 注册掉；
 * tianai 那两个嵌套配置都带 {@code @ConditionalOnMissingBean(CacheStore.class)}，会自动让位。
 * 结果与自动配置顺序无关。
 *
 * <p>这里刻意<b>不</b>加 {@code @ConditionalOnBean(StringRedisTemplate.class)} —— 那正是要摆脱的那种
 * 顺序敏感判断。{@code StringRedisTemplate} 真的缺失时让它启动失败，好过静默退回内存实现：
 * 前者一眼看见，后者要等到用户登不上才发现。
 *
 * <p>2026-09-12 实测当时运行的仍是 Redis 实现（生成验证码后 valkey 里出现 {@code captcha:ROTATE_*} 键），
 * 也就是说这个改动修的是<b>隐患</b>不是<b>现症</b>；但它是靠运气成立的，任何一次依赖升级都可能翻面。
 */
@Slf4j
@AutoConfiguration(before = CacheStoreAutoConfiguration.class)
@ConditionalOnClass({CacheStore.class, RedisCacheStore.class, StringRedisTemplate.class})
public class CaptchaCacheStoreAutoConfiguration {

	@Bean
	public CacheStore captchaRedisCacheStore(StringRedisTemplate stringRedisTemplate) {
		log.info(">>> 验证码 CacheStore 显式绑定为 Redis 实现（不依赖自动配置顺序）");
		return new RedisCacheStore(stringRedisTemplate);
	}
}
