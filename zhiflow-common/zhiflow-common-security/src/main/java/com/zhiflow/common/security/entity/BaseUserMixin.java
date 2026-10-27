package com.zhiflow.common.security.entity;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.annotation.JsonDeserialize;
import org.springframework.security.core.GrantedAuthority;

import java.util.Collection;
import java.util.List;

/**
 * Jackson mixin: BaseUser 没有无参构造，需要把 14 参构造标成 @JsonCreator
 * 才能从 Redis JSON 缓存中反序列化回来。
 *
 * 空 @JsonDeserialize 是为了重置父类 User 上 spring-security UserMixin 注册的
 * UserDeserializer —— 否则 Jackson 反序列化 BaseUser 时会沿继承链走 UserDeserializer，
 * 拿到一个 User 实例（丢失所有 BaseUser 字段），下游 cast 报 ClassCastException。
 *
 * ⚠️ <b>这三个注解必须与「消费本 mixin 的那个 ObjectMapper」同属一套 Jackson。</b>
 * 当前消费者是 RedisTemplateConfig.createValkeyOptimizedJsonSerializer() 的 **Jackson 3**
 * mapper，所以 @JsonDeserialize 取 tools.jackson.databind.annotation；
 * @JsonCreator / @JsonProperty 在 Jackson 2/3 里是同一个构件（com.fasterxml.jackson.annotation），
 * 两代通用，不要跟着改。
 *
 * 2026-09-12 事故：缓存 mapper 还是 Jackson 2 时，这一行被 OpenRewrite 换成了 tools.jackson 版，
 * Jackson 2 认不出 ⇒ 重置失效 ⇒ 全站管理后台每个带令牌的请求 424，登录即被踢出。
 * 回归守卫见 BaseUserCacheRoundTripTest：判据是「读回来的是不是 BaseUser」，不是「有没有报错」。
 */
@JsonDeserialize
public abstract class BaseUserMixin {

    @JsonCreator
    public BaseUserMixin(
            @JsonProperty("id") String id,
            @JsonProperty("organId") String organId,
            @JsonProperty("type") String type,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("username") String username,
            @JsonProperty("password") String password,
            @JsonProperty("tenantIds") List<String> tenantIds,
            @JsonProperty("enabled") boolean enabled,
            @JsonProperty("accountNonExpired") boolean accountNonExpired,
            @JsonProperty("credentialsNonExpired") boolean credentialsNonExpired,
            @JsonProperty("accountNonLocked") boolean accountNonLocked,
            @JsonProperty("authorities") Collection<? extends GrantedAuthority> authorities) {
    }
}
