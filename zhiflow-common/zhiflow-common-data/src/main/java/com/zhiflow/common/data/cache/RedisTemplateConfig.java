package com.zhiflow.common.data.cache;

import com.fasterxml.jackson.annotation.JsonAutoDetect;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.PropertyAccessor;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.entity.BaseUserMixin;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.data.redis.autoconfigure.DataRedisAutoConfiguration;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import org.springframework.security.jackson.SecurityJacksonModules;
import tools.jackson.databind.DefaultTyping;
import tools.jackson.databind.JacksonModule;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.util.List;

/**
 * RedisTemplate 统一配置（Valkey 优化版）
 * 
 * Bean 说明：
 * 1. redisTemplate - 主 Bean，JSON 序列化，适用于大部分业务
 * 2. oauth2RedisTemplate - OAuth2 专用，JDK 序列化
 * 3. stringRedisTemplate - 字符串操作专用
 */
@Slf4j
@EnableCaching
@AllArgsConstructor
@AutoConfiguration(before = DataRedisAutoConfiguration.class, after = ValkeyConnectionConfig.class)
public class RedisTemplateConfig {
    
    private final RedisConnectionFactory redisConnectionFactory;

    @Bean("redisTemplate")
    @Primary
    public RedisTemplate<String, Object> redisTemplate() {
        log.info(">>> 初始化主 RedisTemplate (JSON 序列化)");
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        
        GenericJacksonJsonRedisSerializer jsonSerializer = createValkeyOptimizedJsonSerializer();
        
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(jsonSerializer);
        template.setHashValueSerializer(jsonSerializer);
        template.setConnectionFactory(redisConnectionFactory);
        
        template.afterPropertiesSet();
        
        verifyTemplate(template, "redisTemplate");
        
        log.info("主 RedisTemplate 初始化完成");
        return template;
    }

    @Bean("oauth2RedisTemplate")
    public RedisTemplate<String, Object> oauth2RedisTemplate() {
        log.info(">>> 初始化 OAuth2 RedisTemplate (JDK 序列化)");
        
        RedisTemplate<String, Object> template = new RedisTemplate<>();
        
        template.setKeySerializer(new StringRedisSerializer());
        template.setHashKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(RedisSerializer.java());
        template.setHashValueSerializer(RedisSerializer.java());
        template.setConnectionFactory(redisConnectionFactory);
        
        template.afterPropertiesSet();
        
        verifyTemplate(template, "oauth2RedisTemplate");
        
        log.info("OAuth2 RedisTemplate 初始化完成");
        return template;
    }

    @Bean("stringRedisTemplate")
    public StringRedisTemplate stringRedisTemplate() {
        log.info(">>> 初始化 StringRedisTemplate");
        
        StringRedisTemplate template = new StringRedisTemplate();
        template.setConnectionFactory(redisConnectionFactory);
        template.afterPropertiesSet();
        
        log.info("StringRedisTemplate 初始化完成");
        return template;
    }

    /**
     * 缓存层 JSON 序列化器（Jackson 3）。
     *
     * <p><b>已有的缓存值不需要清</b>：2026-09-12 拿真实旧缓存值（372KB 菜单树、
     * 377KB 字典、登录态 BaseUser）实测过，Jackson 2 写的值 Jackson 3 读得回、反过来也读得回，
     * 归一化后逐字段相同。两者唯一的差别是**字段顺序**（Jackson 3 默认按字母序），
     * 不影响解析——所以判「兼容不兼容」不能用逐字节比对，要用语义比对。
     * 回归守卫见 {@code CacheJackson3CompatExperimentTest}。
     *
     * <p>⚠️ <b>{@link SecurityJacksonModules#getModules(ClassLoader)} 的单参重载不能用。</b>
     * Jackson 3 版的 spring-security 模块自带一套白名单，单参形态装的是只放行 spring-security
     * 类型的严格白名单，会把本项目的类全部拒掉（实测 {@code MenuVO} / {@code R} 都报
     * "Configured PolymorphicTypeValidator denied resolution"，每个走缓存的接口当场 500）。
     * 必须用双参重载，把我们自己的 Builder 交给它，两边的白名单合到同一个 PTV 上。
     * Jackson 2 没有这一层，是迁移时唯一会当场炸的差异。
     */
    public static GenericJacksonJsonRedisSerializer createValkeyOptimizedJsonSerializer() {
        // 限定多态反序列化范围到本项目 + spring-security，避免 LaissezFaire + default typing 的 RCE 风险。
        // java.math / java.time 是值类型常见字段（BigDecimal/Instant/LocalDateTime…），缺一就 500。
        BasicPolymorphicTypeValidator.Builder ptvBuilder = BasicPolymorphicTypeValidator.builder()
                .allowIfBaseType("com.zhiflow.")
                .allowIfBaseType("org.springframework.security.")
                .allowIfSubType("com.zhiflow.")
                .allowIfSubType("org.springframework.security.")
                .allowIfSubType("java.util.")
                .allowIfSubType("java.lang.")
                .allowIfSubType("java.math.")
                .allowIfSubType("java.time.");

        // spring-security 自带 mixin（UserMixin/SimpleGrantedAuthorityMixin/UnmodifiableSetMixin 等），
        // 没它的话 BaseUser.authorities (Collections$UnmodifiableSet) 即使其它字段修好也读不回。
        List<JacksonModule> securityModules = SecurityJacksonModules
                .getModules(RedisTemplateConfig.class.getClassLoader(), ptvBuilder);

        ObjectMapper mapper = JsonMapper.builder()
                .addModules(securityModules)
                // BaseUser 没有无参构造，必须通过 mixin 把 14 参构造标成 @JsonCreator；
                // 且 mixin 上那个空 @JsonDeserialize 必须是 **tools.jackson** 版，与本 mapper 同代。
                // 2026-09-12 事故：它曾被写成 Jackson 2 版注解配 Jackson 2 mapper，后者是当时的正确搭配；
                // 现在 mapper 迁到 Jackson 3，注解也必须跟着迁，否则 BaseUser 会被降级成父类 User，
                // 全站管理后台每个带令牌的请求 424。守卫见 BaseUserCacheRoundTripTest。
                .addMixIn(BaseUser.class, BaseUserMixin.class)
                .changeDefaultVisibility(vc -> vc
                        .withVisibility(PropertyAccessor.ALL, JsonAutoDetect.Visibility.ANY)
                        .withVisibility(PropertyAccessor.FIELD, JsonAutoDetect.Visibility.ANY))
                // Jackson 3 里 java.time 已并入 databind，不再需要 JavaTimeModule；
                // WRITE_DATES_AS_TIMESTAMPS 移到 DateTimeFeature 且默认 false，FAIL_ON_EMPTY_BEANS 默认 false，
                // 三个旧开关都不用再显式设置（已用真实缓存值实测等价，不是查文档推的）。
                .activateDefaultTyping(ptvBuilder.build(), DefaultTyping.NON_FINAL, JsonTypeInfo.As.PROPERTY)
                .build();

        log.debug("Valkey JSON 序列化器配置完成（Jackson 3）");
        return new GenericJacksonJsonRedisSerializer(mapper);
    }

    private void verifyTemplate(RedisTemplate<String, Object> template, String beanName) {
        boolean valid = true;
        
        if (template.getKeySerializer() == null) {
            log.error("{} - KeySerializer 未配置", beanName);
            valid = false;
        }
        
        if (template.getValueSerializer() == null) {
            log.error("{} - ValueSerializer 未配置", beanName);
            valid = false;
        }
        
        if (template.getConnectionFactory() == null) {
            log.error("{} - ConnectionFactory 未配置", beanName);
            valid = false;
        }
        
        if (valid) {
            log.debug("{} 配置验证通过", beanName);
        } else {
            throw new IllegalStateException(beanName + " 配置验证失败");
        }
    }
}
