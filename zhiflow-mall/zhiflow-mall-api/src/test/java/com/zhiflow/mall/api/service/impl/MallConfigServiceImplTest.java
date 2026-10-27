package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.github.benmanes.caffeine.cache.Cache;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.zhiflow.mall.api.mapper.MallConfigMapper;
import com.zhiflow.mall.common.entity.MallConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@code getConfigsByTenantId} 空结果缓存 + 进程内短 TTL 缓存（10-23 删开关，恒开）。
 *
 * <p>手搓一个最小的「假 Redis」（{@code fakeRedis} 这个 {@code HashMap}）而不是单纯 stub 固定返回值：
 * 这样 {@code set} 真的会影响下一次 {@code get}，才测得出「写缓存之后下一次不再查库」「删 key 之后下一次重新查库」
 * 这类跨调用的时序行为，而不是只测单次调用的分支覆盖。
 */
@ExtendWith(MockitoExtension.class)
class MallConfigServiceImplTest {

    @Mock
    private MallConfigMapper mallConfigMapper;

    @Mock
    private RedisTemplate<String, Object> redisTemplate;

    @Mock
    private ValueOperations<String, Object> valueOperations;

    private MallConfigServiceImpl service;

    /** 模拟 Redis：{@code set} 写进去的值，下一次同 key 的 {@code get} 要能读出来。 */
    private Map<String, Object> fakeRedis;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MybatisConfiguration configuration = new MybatisConfiguration();
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(configuration, "");
        assistant.setCurrentNamespace("test");
        TableInfoHelper.initTableInfo(assistant, MallConfig.class);
    }

    @BeforeEach
    void setUp() {
        service = new MallConfigServiceImpl(redisTemplate);
        ReflectionTestUtils.setField(service, "baseMapper", mallConfigMapper);

        fakeRedis = new HashMap<>();
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        when(valueOperations.get(anyString())).thenAnswer(inv -> fakeRedis.get(inv.getArgument(0, String.class)));
        // ValueOperations#set 返回 void，不能用 when(...).thenAnswer(...)（void 方法不能作为 when 的实参），
        // 得用 doAnswer(...).when(mock).set(...)
        doAnswer(inv -> {
            fakeRedis.put(inv.getArgument(0, String.class), inv.getArgument(1));
            return null;
        }).when(valueOperations).set(anyString(), any(), anyLong(), any(TimeUnit.class));

        // 每个用例开始前清本地静态缓存，避免跨用例/跨 tenantId 串值（LOCAL_CACHE 是静态字段，单例 bean 语义）
        localCache().invalidateAll();
    }

    @AfterEach
    void tearDown() {
        localCache().invalidateAll();
    }

    @SuppressWarnings("unchecked")
    private static Cache<String, Map<String, Object>> localCache() {
        return (Cache<String, Map<String, Object>>) ReflectionTestUtils.getField(MallConfigServiceImpl.class, "LOCAL_CACHE");
    }

    private static MallConfig buildConfig(String tenantId, String key, String value, String type) {
        MallConfig config = new MallConfig();
        config.setId("cfg-" + key);
        config.setTenantId(tenantId);
        config.setConfigKey(key);
        config.setConfigValue(value);
        config.setConfigType(type);
        return config;
    }

    // ---------------------------------------------------------------------
    // 1. 空配置租户连续调用只查库一次（两次调用之间清本地缓存，模拟 5 秒 TTL 过期，只测 Redis 层）
    // ---------------------------------------------------------------------

    @Test
    void emptyTenantOnlyHitsDbOnce() {
        String tenantId = "tenant-empty";
        when(mallConfigMapper.selectList(any())).thenReturn(List.of());

        Map<String, Object> first = service.getConfigsByTenantId(tenantId);
        localCache().invalidateAll();
        Map<String, Object> second = service.getConfigsByTenantId(tenantId);

        assertThat(first).isEmpty();
        assertThat(second).isEmpty();
        // 关键断言：空结果第一次查库之后也写进了 Redis，第二次直接命中 Redis，不再查库
        verify(mallConfigMapper, times(1)).selectList(any());
        assertThat(fakeRedis).containsKey("mall:config:" + tenantId);
        // 第二次确实经过了 Redis（本地缓存已清），不是本地缓存挡掉的
        verify(valueOperations, times(2)).get(anyString());
    }

    // ---------------------------------------------------------------------
    // 2. 后台改配置后（模拟 mall-admin clearCache 删 key）新值生效
    // ---------------------------------------------------------------------

    @Test
    void afterCacheEvictedNewConfigIsVisible() {
        String tenantId = "tenant-evict";
        String cacheKey = "mall:config:" + tenantId;

        // 第一次：0 行，空结果被缓存
        when(mallConfigMapper.selectList(any())).thenReturn(List.of());
        Map<String, Object> before = service.getConfigsByTenantId(tenantId);
        assertThat(before).isEmpty();
        assertThat(fakeRedis).containsKey(cacheKey);

        // 模拟 mall-admin 保存配置后调用 clearCache()：删掉同一个 mall:config:{tenantId} key；
        // 进程内本地缓存最多 5 秒后过期（已接受的生效延迟），这里直接清掉代表过期之后
        fakeRedis.remove(cacheKey);
        localCache().invalidateAll();

        // 后台新增了一条配置
        when(mallConfigMapper.selectList(any()))
                .thenReturn(List.of(buildConfig(tenantId, "feature.newly_added", "true", "boolean")));

        Map<String, Object> after = service.getConfigsByTenantId(tenantId);

        assertThat(after).containsEntry("feature.newly_added", true);
        verify(mallConfigMapper, times(2)).selectList(any());
    }

    // ---------------------------------------------------------------------
    // 3. 本地缓存：命中后不再打 Redis / DB
    // ---------------------------------------------------------------------

    @Test
    void localCacheSkipsRedisAndDbOnHit() {
        String tenantId = "tenant-local-on";
        when(mallConfigMapper.selectList(any()))
                .thenReturn(List.of(buildConfig(tenantId, "feature.x", "true", "boolean")));

        Map<String, Object> first = service.getConfigsByTenantId(tenantId);
        assertThat(first).containsEntry("feature.x", true);
        verify(mallConfigMapper, times(1)).selectList(any());
        verify(valueOperations, times(1)).get(anyString());

        Map<String, Object> second = service.getConfigsByTenantId(tenantId);
        assertThat(second).containsEntry("feature.x", true);

        // 第二次应该直接命中本地缓存：不应再多调一次 selectList / Redis get
        verify(mallConfigMapper, times(1)).selectList(any());
        verify(valueOperations, times(1)).get(anyString());
    }

    // ---------------------------------------------------------------------
    // 4. 返回值不可变：本地缓存命中返回的 Map 不能被调用方修改
    // ---------------------------------------------------------------------

    @Test
    void localCacheReturnsImmutableMapOnHit() {
        String tenantId = "tenant-immutable";
        when(mallConfigMapper.selectList(any()))
                .thenReturn(List.of(buildConfig(tenantId, "feature.w", "true", "boolean")));

        service.getConfigsByTenantId(tenantId); // 第一次：落地到本地缓存
        Map<String, Object> cached = service.getConfigsByTenantId(tenantId); // 第二次：本地缓存命中

        assertThatThrownBy(() -> cached.put("x", "y"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    // ---------------------------------------------------------------------
    // 5. 集成复审第 1 条：空租户号（null / ""）不能 NPE，且完全不碰本地缓存，只走 Redis/DB——
    //    Caffeine 的 getIfPresent/put 都不接受 null 键，
    //    没带 token 也没带 tenant-id 头的公开配置接口请求会落到这个分支。
    // ---------------------------------------------------------------------

    @ParameterizedTest
    @NullAndEmptySource
    void blankTenantIdBypassesLocalCache(String tenantId) {
        when(mallConfigMapper.selectList(any()))
                .thenReturn(List.of(buildConfig(tenantId, "feature.blank-tenant", "true", "boolean")));

        Map<String, Object> first = service.getConfigsByTenantId(tenantId);
        Map<String, Object> second = service.getConfigsByTenantId(tenantId);

        assertThat(first).containsEntry("feature.blank-tenant", true);
        assertThat(second).isEqualTo(first);
        // 两次都经过 Redis（第二次命中 Redis；key 是字符串拼接，null/"" 都是合法字面量），只查一次库
        verify(valueOperations, times(2)).get(anyString());
        verify(mallConfigMapper, times(1)).selectList(any());
        assertThat(fakeRedis).containsKey("mall:config:" + tenantId);
        // 关键断言：本地缓存必须完全没被碰过，不止是不抛异常
        assertThat(localCache().estimatedSize()).isZero();
    }

    @Test
    void getPublicConfigsStillWorksWithLocalCache() {
        String tenantId = "tenant-public";
        when(mallConfigMapper.selectList(any())).thenReturn(List.of(
                buildConfig(tenantId, "prescription.audit_required", "true", "boolean"),
                buildConfig(tenantId, "internal.secret", "nope", "string")
        ));

        com.zhiflow.common.data.tenant.TenantContextHolder.setTenantId(tenantId);
        try {
            service.getAllConfigs(); // 预热本地缓存
            Map<String, Object> publicConfigs = service.getPublicConfigs(); // 走本地缓存命中分支

            assertThat(publicConfigs).containsEntry("prescription.audit_required", true);
            assertThat(publicConfigs).doesNotContainKey("internal.secret");
            // getPublicConfigs 内部自己 new 了一份 HashMap，可以正常 put，证明没有被不可变视图污染
            publicConfigs.put("extra", "ok");
            assertThat(publicConfigs).containsEntry("extra", "ok");
        } finally {
            com.zhiflow.common.data.tenant.TenantContextHolder.clear();
        }
    }
}
