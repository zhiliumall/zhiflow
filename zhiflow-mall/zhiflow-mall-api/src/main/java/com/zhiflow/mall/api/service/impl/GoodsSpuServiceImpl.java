/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONArray;
import cn.hutool.json.JSONException;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.search.util.MeiliSearchUtil;
import com.zhiflow.mall.api.cache.GoodsDetailCache;
import com.zhiflow.mall.api.cache.GoodsDetailSnapshot;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.service.GoodsBrandService;
import com.zhiflow.mall.api.service.GoodsSpuService;
import com.zhiflow.mall.api.service.ShopInfoService;
import com.zhiflow.mall.api.vo.GoodsParameterItem;
import com.zhiflow.mall.common.dto.GoodsDetailAggregateDTO;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import com.meilisearch.sdk.SearchRequest;
import com.meilisearch.sdk.model.SearchResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * spu商品（C 端）。
 *
 * <p>搜索口径（K-H3）：启用 MeiliSearch 且有关键词时查索引 {@code goods_spu_{tenantId}}，否则查库；
 * 查索引失败一律降级查库，保证「能出结果」。列表固定只返回已上架、已过审的商品。
 *
 * @date 2019-08-12 16:25:10
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsSpuServiceImpl extends ServiceImpl<GoodsSpuMapper, GoodsSpu> implements GoodsSpuService {

    private static final ObjectMapper OBJECT_MAPPER = new JsonMapper();

    /** 列表卡片不消费的大字段（富文本详情 / 规格 JSON / 视频），只在详情页查。 */
    private static final Set<String> LIST_HEAVY_FIELDS = Set.of("description", "parameterJson", "videoUrl");

    /** 与 {@link #LIST_HEAVY_FIELDS} 同一份字段，DB 分支按列名裁剪 select。 */
    private static final Set<String> LIST_HEAVY_COLUMNS = Set.of("description", "parameter_json", "video_url");

    private final GoodsSkuMapper goodsSkuMapper;
    private final GoodsBrandService goodsBrandService;
    private final GoodsDetailCache goodsDetailCache;
    private final ShopInfoService shopInfoService;
    /** MeiliSearch 可选（{@code meilisearch.enabled}），没有这个 Bean 时搜索查库。 */
    private final ObjectProvider<MeiliSearchUtil> meiliSearchUtil;

    /**
     * 商品主数据：静态快照（10 分钟）叠加库存销量（5 秒），两份都走缓存，见 {@link GoodsDetailCache}。
     * SKU 的成本价不在快照里（查询就不取），匿名可读的商详不会带出去。
     */
    @Override
    public GoodsSpu getById2Optimized(String id) {
        GoodsDetailSnapshot snapshot = goodsDetailCache.getDetail(id);
        return snapshot == null ? null : toGoodsSpu(snapshot);
    }

    private GoodsSpu toGoodsSpu(GoodsDetailSnapshot snapshot) {
        GoodsSpu goodsSpu = snapshot.toGoodsSpu(goodsDetailCache.getStock(snapshot.getId()));
        if (StrUtil.isNotBlank(goodsSpu.getBrandId())) {
            GoodsBrand brand = goodsBrandService.getById(goodsSpu.getBrandId());
            if (brand != null) {
                goodsSpu.setGoodsBrand(brand);
            }
        }
        return goodsSpu;
    }

    @Override
    public GoodsDetailAggregateDTO getGoodsDetailAggregate(String id) {
        GoodsDetailSnapshot snapshot = goodsDetailCache.getDetail(id);
        if (snapshot == null) {
            return null;
        }
        GoodsSpu goodsSpu = toGoodsSpu(snapshot);
        GoodsDetailAggregateDTO result = new GoodsDetailAggregateDTO();
        result.setGoodsSpu(goodsSpu);
        List<GoodsSpecVO> specTree = snapshot.toSpecTree();
        if (CollectionUtil.isNotEmpty(specTree)) {
            result.setSpecTree(specTree);
        }
        if (StrUtil.isNotBlank(goodsSpu.getShopId())) {
            ShopInfo shopInfo = shopInfoService.getById(goodsSpu.getShopId());
            if (shopInfo != null) {
                result.setShopInfo(shopInfo);
            }
        }
        return result;
    }

    @Override
    public List<GoodsSpu> listCategorySpu(String categorySecondId) {
        return listCategorySpuBatch(List.of(categorySecondId));
    }

    @Override
    public List<GoodsSpu> listCategorySpuBatch(List<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return new ArrayList<>();
        }
        return this.list(onShelf(new LambdaQueryWrapper<GoodsSpu>())
                .select(GoodsSpu.class, info -> !LIST_HEAVY_COLUMNS.contains(info.getColumn()))
                .in(GoodsSpu::getCategorySecond, categoryIds)
                .orderByAsc(GoodsSpu::getSort));
    }

    @Override
    public IPage<LinkedHashMap> page(IPage<LinkedHashMap> page, GoodsSpu goodsSpu, BigDecimal minPrice,
                                     BigDecimal maxPrice, String tab) {
        MeiliSearchUtil meili = meiliSearchUtil.getIfAvailable();
        if (meili != null && StrUtil.isNotBlank(goodsSpu.getName())) {
            try {
                return pageFromIndex(meili, page, goodsSpu, minPrice, maxPrice, tab);
            }
            catch (Exception e) {
                log.warn("MeiliSearch 查询失败，降级查库: name={}, error={}", goodsSpu.getName(), e.getMessage());
            }
        }
        return pageFromDb(page, goodsSpu, minPrice, maxPrice, tab);
    }

    private IPage<LinkedHashMap> pageFromIndex(MeiliSearchUtil meili, IPage<LinkedHashMap> page, GoodsSpu goodsSpu,
                                               BigDecimal minPrice, BigDecimal maxPrice, String tab) throws Exception {
        SearchRequest.SearchRequestBuilder request = SearchRequest.builder()
                .q(goodsSpu.getName())
                .filter(new String[]{indexFilter(goodsSpu, minPrice, maxPrice)})
                .offset((int) ((page.getCurrent() - 1) * page.getSize()))
                .limit((int) page.getSize());
        String[] sort = indexSort(tab);
        if (sort.length > 0) {
            request.sort(sort);
        }
        SearchResult result = (SearchResult) meili.index(indexName()).search(request.build());
        List<LinkedHashMap> records = new ArrayList<>();
        if (result.getHits() != null) {
            for (HashMap<String, Object> hit : result.getHits()) {
                records.add(new LinkedHashMap<>(hit));
            }
        }
        normalizeArrayFields(records);
        // 索引文档的 id 是原始雪花 id（写入时复位了 @HashidsId，见 mall-admin MeiliSearchSyncServiceImpl）
        fillSkuStock(records, records.stream().map(r -> r.get("id"))
                .map(id -> id == null ? null : String.valueOf(id)).collect(Collectors.toList()));
        stripHeavyFields(records);
        page.setTotal(result.getEstimatedTotalHits() > 0 ? result.getEstimatedTotalHits() : records.size());
        page.setRecords(records);
        return page;
    }

    private IPage<LinkedHashMap> pageFromDb(IPage<LinkedHashMap> page, GoodsSpu goodsSpu, BigDecimal minPrice,
                                            BigDecimal maxPrice, String tab) {
        LambdaQueryWrapper<GoodsSpu> wrapper = onShelf(new LambdaQueryWrapper<GoodsSpu>())
                .select(GoodsSpu.class, info -> !LIST_HEAVY_COLUMNS.contains(info.getColumn()))
                .like(StrUtil.isNotBlank(goodsSpu.getName()), GoodsSpu::getName, goodsSpu.getName())
                .eq(StrUtil.isNotBlank(goodsSpu.getCategoryFirst()), GoodsSpu::getCategoryFirst, goodsSpu.getCategoryFirst())
                .eq(StrUtil.isNotBlank(goodsSpu.getCategorySecond()), GoodsSpu::getCategorySecond, goodsSpu.getCategorySecond())
                .eq(StrUtil.isNotBlank(goodsSpu.getCategoryThird()), GoodsSpu::getCategoryThird, goodsSpu.getCategoryThird())
                .eq(StrUtil.isNotBlank(goodsSpu.getBrandId()), GoodsSpu::getBrandId, goodsSpu.getBrandId())
                .ge(minPrice != null, GoodsSpu::getPriceDown, minPrice)
                .le(maxPrice != null, GoodsSpu::getPriceDown, maxPrice);
        switch (StrUtil.blankToDefault(tab, TAB_DEFAULT)) {
            case TAB_SALES -> wrapper.orderByDesc(GoodsSpu::getSaleNum);
            case TAB_PRICE_ASC -> wrapper.orderByAsc(GoodsSpu::getPriceDown);
            case TAB_PRICE_DESC -> wrapper.orderByDesc(GoodsSpu::getPriceDown);
            case TAB_NEWEST -> wrapper.orderByDesc(GoodsSpu::getCreateTime);
            default -> wrapper.orderByAsc(GoodsSpu::getSort).orderByDesc(GoodsSpu::getCreateTime);
        }
        IPage<GoodsSpu> dbPage = this.page(new Page<>(page.getCurrent(), page.getSize()), wrapper);
        page.setTotal(dbPage.getTotal());
        page.setRecords(toListRecords(dbPage.getRecords()));
        return page;
    }

    @Override
    public List<LinkedHashMap> listGoodsSpuByIds(List<String> ids) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<>();
        }
        List<GoodsSpu> goodsSpuList = this.list(onShelf(new LambdaQueryWrapper<GoodsSpu>())
                .select(GoodsSpu.class, info -> !LIST_HEAVY_COLUMNS.contains(info.getColumn()))
                .in(GoodsSpu::getId, ids));
        Map<String, GoodsSpu> byId = goodsSpuList.stream()
                .collect(Collectors.toMap(GoodsSpu::getId, spu -> spu, (a, b) -> a));
        List<GoodsSpu> ordered = ids.stream().map(byId::get).filter(spu -> spu != null).distinct().toList();
        return toListRecords(ordered);
    }

    @Override
    public int increaseSaleNum(String spuId, int delta) {
        // 原子累加；搜索索引的销量由 mall-admin 的写库后同步与「同步搜索」重建刷新
        return baseMapper.increaseSaleNum(spuId, delta);
    }

    @Override
    public List<GoodsParameterItem> listParameters(String spuId) {
        // 与商详共用同一份快照（可见口径一致：已过审即可看，下架商品详情页仍能打开）
        GoodsDetailSnapshot snapshot = goodsDetailCache.getDetail(spuId);
        return snapshot == null ? List.of() : parseParameters(snapshot.getParameterJson());
    }

    /**
     * 解析后台保存的 {@code parameter_json}：{@code {"id": 模板id, "data": [{"name", "type", "value"}]}}。
     * 多选参数的 value 是数组，用「、」拼接；名称或值为空的行不展示；格式不认识时返回空列表而不是报错。
     */
    static List<GoodsParameterItem> parseParameters(String parameterJson) {
        if (StrUtil.isBlank(parameterJson) || !JSONUtil.isTypeJSONObject(parameterJson)) {
            return List.of();
        }
        Object data;
        try {
            data = JSONUtil.parseObj(parameterJson).get("data");
        }
        catch (JSONException e) {
            return List.of();
        }
        if (!(data instanceof JSONArray rows)) {
            return List.of();
        }
        List<GoodsParameterItem> items = new ArrayList<>();
        for (Object row : rows) {
            if (!(row instanceof JSONObject item)) {
                continue;
            }
            Object raw = item.get("value");
            String value = raw instanceof JSONArray values
                    ? values.stream().map(String::valueOf).filter(StrUtil::isNotBlank).collect(Collectors.joining("、"))
                    : raw == null || JSONUtil.isNull(raw) ? null : StrUtil.trim(String.valueOf(raw));
            String name = StrUtil.trim(item.getStr("name"));
            if (StrUtil.isNotBlank(name) && StrUtil.isNotBlank(value)) {
                items.add(new GoodsParameterItem(name, value));
            }
        }
        return items;
    }

    /** 已上架、已过审、未删除。 */
    private static LambdaQueryWrapper<GoodsSpu> onShelf(LambdaQueryWrapper<GoodsSpu> wrapper) {
        return wrapper.eq(GoodsSpu::getShelf, "1")
                .eq(GoodsSpu::getVerifyStatus, "1")
                .eq(GoodsSpu::getDelFlag, "0");
    }

    /**
     * DB 实体转列表项。⚠️ 转 Map 后 {@code id} 已被 {@code @HashidsId} 编码，按库里的 spu_id 关联
     * 必须用实体上的原始 id（与 records 同序同长传给 {@link #fillSkuStock}）。
     */
    private List<LinkedHashMap> toListRecords(List<GoodsSpu> goodsSpuList) {
        List<LinkedHashMap> records = new ArrayList<>();
        for (GoodsSpu goodsSpu : goodsSpuList) {
            records.add(new LinkedHashMap<>(OBJECT_MAPPER.convertValue(goodsSpu, Map.class)));
        }
        normalizeArrayFields(records);
        fillSkuStock(records, goodsSpuList.stream().map(GoodsSpu::getId).collect(Collectors.toList()));
        stripHeavyFields(records);
        return records;
    }

    /**
     * 按 SPU 聚合在售 SKU 的库存，回填列表项的 {@code stock}（goods_spu 表没有 stock 列）。
     * 查不到在售 SKU 记 0（无货），不留 null。
     *
     * @param rawSpuIds 与 {@code records} 同序同长的原始雪花 spuId
     */
    private void fillSkuStock(List<LinkedHashMap> records, List<String> rawSpuIds) {
        List<String> queryIds = rawSpuIds.stream().filter(StrUtil::isNotBlank).distinct().collect(Collectors.toList());
        if (records.isEmpty() || queryIds.isEmpty()) {
            return;
        }
        Map<String, Integer> stockBySpu = new HashMap<>();
        for (GoodsSku sku : goodsSkuMapper.selectList(new LambdaQueryWrapper<GoodsSku>()
                .select(GoodsSku::getSpuId, GoodsSku::getStock)
                .in(GoodsSku::getSpuId, queryIds)
                .eq(GoodsSku::getEnable, "1"))) {
            stockBySpu.merge(sku.getSpuId(), sku.getStock() == null ? 0 : sku.getStock(), Integer::sum);
        }
        int size = Math.min(records.size(), rawSpuIds.size());
        for (int i = 0; i < size; i++) {
            records.get(i).put("stock", stockBySpu.getOrDefault(rawSpuIds.get(i), 0));
        }
    }

    /** 索引文档与 Jackson 转换都可能把数组字段存成 JSON 字符串，这里还原成数组。 */
    private static void normalizeArrayFields(List<LinkedHashMap> records) {
        for (LinkedHashMap map : records) {
            Object value = map.get("picUrls");
            if (value instanceof String str && str.trim().startsWith("[")) {
                try {
                    map.put("picUrls", JSONUtil.parseArray(str).toList(String.class));
                }
                catch (Exception e) {
                    log.debug("picUrls 反序列化失败，保留原值");
                }
            }
        }
    }

    private static void stripHeavyFields(List<LinkedHashMap> records) {
        for (LinkedHashMap record : records) {
            LIST_HEAVY_FIELDS.forEach(record::remove);
        }
    }

    private static String indexFilter(GoodsSpu goodsSpu, BigDecimal minPrice, BigDecimal maxPrice) {
        List<String> filters = new ArrayList<>();
        filters.add("shelf = \"1\"");
        filters.add("verifyStatus = \"1\"");
        addEq(filters, "categoryFirst", goodsSpu.getCategoryFirst());
        addEq(filters, "categorySecond", goodsSpu.getCategorySecond());
        addEq(filters, "categoryThird", goodsSpu.getCategoryThird());
        addEq(filters, "brandId", goodsSpu.getBrandId());
        if (minPrice != null) {
            filters.add("priceDown >= " + minPrice.toPlainString());
        }
        if (maxPrice != null) {
            filters.add("priceDown <= " + maxPrice.toPlainString());
        }
        return String.join(" AND ", filters);
    }

    /** 过滤值来自请求参数：只放行雪花 id 形态（纯数字），防止拼进过滤表达式。 */
    private static void addEq(List<String> filters, String field, String value) {
        if (StrUtil.isNotBlank(value) && value.matches("\\d{1,32}")) {
            filters.add(field + " = \"" + value + "\"");
        }
    }

    /** 综合序 = 索引相关性；其余 tab 用索引的 sortable 字段。 */
    private static String[] indexSort(String tab) {
        return switch (StrUtil.blankToDefault(tab, TAB_DEFAULT)) {
            case TAB_SALES -> new String[]{"saleNum:desc"};
            case TAB_PRICE_ASC -> new String[]{"priceDown:asc"};
            case TAB_PRICE_DESC -> new String[]{"priceDown:desc"};
            case TAB_NEWEST -> new String[]{"createTime:desc"};
            default -> new String[0];
        };
    }

    private static String indexName() {
        return "goods_spu_" + TenantContextHolder.getTenantId();
    }
}
