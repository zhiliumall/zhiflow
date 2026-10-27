package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.search.util.MeiliSearchUtil;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.service.MeiliSearchSyncService;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.meilisearch.sdk.model.DocumentsQuery;
import com.meilisearch.sdk.model.Results;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.annotation.JsonSerialize;
import tools.jackson.databind.json.JsonMapper;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 商品搜索索引同步实现：write-through + 重建索引，索引名 {@code goods_spu_{tenantId}}。
 *
 */
@Slf4j
@Service
@RequiredArgsConstructor
@ConditionalOnProperty(name = "meilisearch.enabled", havingValue = "true")
public class MeiliSearchSyncServiceImpl implements MeiliSearchSyncService {

    private static final int PAGE_SIZE = 500;

    /** C 端搜索用到的过滤字段（mall-api GoodsSpuServiceImpl#indexFilter）。 */
    static final String[] FILTERABLE = {"id", "shelf", "verifyStatus", "delFlag", "categoryFirst", "categorySecond",
            "categoryThird", "brandId", "priceDown"};

    /** C 端排序 tab 用到的排序字段（mall-api GoodsSpuServiceImpl#indexSort）。 */
    static final String[] SORTABLE = {"saleNum", "priceDown", "createTime", "sort"};

    private final MeiliSearchUtil meiliSearchUtil;
    private final GoodsSpuMapper goodsSpuMapper;
    private final ObjectMapper objectMapper = newIndexObjectMapper();

    /**
     * 重建索引时删除幽灵文档的安全阀：待删占索引总量的比例超过此值时只告警不删。
     * 库查询异常、租户上下文丢失都会让活数据集异常偏小，没有这道闸就会把整个索引清空。
     */
    @Value("${mall.search.reconcile.delete-stale.max-ratio:0.35}")
    private double reconcileMaxDeleteRatio;

    /**
     * 索引写入专用的 ObjectMapper：索引主键必须是原始雪花 id。
     *
     * <p>{@code GoodsSpu.id} 上的 {@code @HashidsId} 是注解内联的，对任何 ObjectMapper 都生效；
     * 配了 {@code hashids.salt} 时它会把主键编码成 hashid，而读侧按原始 id 做 {@code id IN [...]} 过滤，
     * 结果恒零命中且没有任何报错。这里用 mixin 把 id 的序列化器复位成默认。
     */
    static ObjectMapper newIndexObjectMapper() {
        return JsonMapper.builder()
                .addMixIn(GoodsSpu.class, RawIdMixin.class)
                .addMixIn(GoodsSku.class, RawIdMixin.class)
                .build();
    }

    /** 把 {@code @HashidsId} 声明的序列化器复位成默认（mixin 注解优先级高于目标类字段注解）。 */
    abstract static class RawIdMixin {

        @JsonSerialize(using = ValueSerializer.None.class)
        @JsonDeserialize(using = ValueDeserializer.None.class)
        String id;

    }

    @Override
    public int fullSyncByTenantId(String tenantId) throws Exception {
        String indexName = indexName(tenantId);
        // 显式设置租户上下文：从后台按钮进来时已有，但重建可能由别的线程触发，search_path 依赖它
        String prevTenant = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            ensureIndex(indexName);
            // 主键 keyset 游标分页逐页写索引，避免把整租户商品一次读进内存。
            // id 列是 varchar 存的 19 位雪花值，字符串序 == 数值序。
            String lastId = null;
            int total = 0;
            Set<String> liveIds = new HashSet<>();
            while (true) {
                List<GoodsSpu> pageList = goodsSpuMapper.selectList(
                        new LambdaQueryWrapper<GoodsSpu>()
                                .eq(GoodsSpu::getTenantId, tenantId)
                                .gt(lastId != null, GoodsSpu::getId, lastId)
                                .orderByAsc(GoodsSpu::getId)
                                .last("limit " + PAGE_SIZE)
                );
                if (pageList.isEmpty()) {
                    break;
                }
                writeGoodsToIndex(indexName, pageList);
                pageList.forEach(spu -> liveIds.add(spu.getId()));
                lastId = pageList.get(pageList.size() - 1).getId();
                total += pageList.size();
                if (pageList.size() < PAGE_SIZE) {
                    break;
                }
            }

            if (total == 0) {
                // 0 条也可能是 schema 切错或查询异常，绝不能拿空集合去做差集删除
                log.info("租户 {} 无商品数据，跳过重建与差集删除", tenantId);
                return 0;
            }

            log.info("租户 {} 重建索引完成，共写入 {} 条商品", tenantId, total);
            deleteStaleDocuments(indexName, tenantId, liveIds);
            return total;
        } finally {
            restoreTenant(prevTenant);
        }
    }

    @Override
    public void syncOneGoods(String tenantId, String goodsId) throws Exception {
        if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(goodsId)) {
            log.warn("syncOneGoods 缺少参数: tenantId={}, goodsId={}", tenantId, goodsId);
            return;
        }
        String indexName = indexName(tenantId);
        String prevTenant = TenantContextHolder.getTenantId();
        TenantContextHolder.setTenantId(tenantId);
        try {
            GoodsSpu goods = goodsSpuMapper.selectById(goodsId);
            if (goods == null) {
                meiliSearchUtil.deleteDocument(indexName, goodsId);
                return;
            }
            writeGoodsToIndex(indexName, Collections.singletonList(goods));
        } finally {
            restoreTenant(prevTenant);
        }
    }

    @Override
    public void deleteOneGoods(String tenantId, String goodsId) throws Exception {
        if (StrUtil.isBlank(tenantId) || StrUtil.isBlank(goodsId)) {
            log.warn("deleteOneGoods 缺少参数: tenantId={}, goodsId={}", tenantId, goodsId);
            return;
        }
        meiliSearchUtil.deleteDocument(indexName(tenantId), goodsId);
    }

    private static String indexName(String tenantId) {
        return "goods_spu_" + tenantId;
    }

    /**
     * 把一批商品写入索引：确保索引存在 → 序列化 → 批量 upsert。重建与单条同步共用，保证文档结构一致。
     */
    private void writeGoodsToIndex(String indexName, List<GoodsSpu> goodsList) throws Exception {
        if (goodsList == null || goodsList.isEmpty()) {
            return;
        }
        if (!meiliSearchUtil.testIndexExists(indexName)) {
            ensureIndex(indexName);
        }
        List<String> documents = new ArrayList<>();
        for (GoodsSpu goods : goodsList) {
            documents.add(objectMapper.writeValueAsString(goods));
        }
        meiliSearchUtil.addOrUpdateDocuments(indexName, documents);
    }

    /** 建索引（主键 id）并下发过滤 / 排序字段。重复调用无副作用。 */
    private void ensureIndex(String indexName) throws Exception {
        if (!meiliSearchUtil.testIndexExists(indexName)) {
            meiliSearchUtil.createIndex(indexName, "id");
        }
        meiliSearchUtil.configureFilterableAndSortableAttributes(indexName, FILTERABLE, SORTABLE);
    }

    /**
     * 重建的「删」那一半：把索引里存在、库中已不存在（或已软删）的文档删掉。
     * 待删占比超过 {@link #reconcileMaxDeleteRatio} 时只告警不删。
     */
    private void deleteStaleDocuments(String indexName, String tenantId, Set<String> liveIds) {
        try {
            Set<String> indexIds = fetchAllIndexDocumentIds(indexName);
            if (indexIds.isEmpty()) {
                return;
            }
            List<String> stale = indexIds.stream().filter(id -> !liveIds.contains(id)).toList();
            if (stale.isEmpty()) {
                return;
            }
            double ratio = (double) stale.size() / indexIds.size();
            if (ratio > reconcileMaxDeleteRatio) {
                log.error("重建索引待删占比 {}% 超过阈值 {}%，本次只告警不删，请先确认库查询是否异常: "
                                + "index={}, 索引 {} 篇, 待删 {} 篇",
                        Math.round(ratio * 100), Math.round(reconcileMaxDeleteRatio * 100),
                        indexName, indexIds.size(), stale.size());
                return;
            }
            meiliSearchUtil.deleteDocuments(indexName, stale);
            log.info("重建索引删除库中已不存在的文档: index={}, tenantId={}, 删除 {} 篇",
                    indexName, tenantId, stale.size());
        }
        catch (Exception e) {
            // 删除失败不影响已完成的 upsert
            log.error("重建索引差集删除失败: index={}, tenantId={}", indexName, tenantId, e);
        }
    }

    /**
     * 分页拉取索引内全部文档 id（只取 id 字段）。
     */
    private Set<String> fetchAllIndexDocumentIds(String indexName) throws Exception {
        Set<String> ids = new HashSet<>();
        int offset = 0;
        final int limit = 1000;
        while (true) {
            DocumentsQuery query = new DocumentsQuery().setLimit(limit).setOffset(offset).setFields(new String[]{"id"});
            Results<Map> page = meiliSearchUtil.getIndex(indexName).getDocuments(query, Map.class);
            Map[] results = page.getResults();
            if (results == null || results.length == 0) {
                break;
            }
            for (Map doc : results) {
                Object id = doc.get("id");
                if (id != null) {
                    ids.add(String.valueOf(id));
                }
            }
            if (results.length < limit) {
                break;
            }
            offset += limit;
        }
        return ids;
    }

    /**
     * 还原线程的租户上下文（线程池复用时避免污染下一个任务）。
     */
    private void restoreTenant(String prevTenant) {
        if (StrUtil.isBlank(prevTenant)) {
            TenantContextHolder.clear();
        } else {
            TenantContextHolder.setTenantId(prevTenant);
        }
    }
}
