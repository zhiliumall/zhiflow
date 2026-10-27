package com.zhiflow.mall.admin.service;

/**
 * 商品搜索索引同步（MeiliSearch 可选，{@code meilisearch.enabled=true} 时才有这个 Bean）。
 *
 * <p>同步方式是 write-through：商品增删改、上下架的事务提交后直接写索引；
 * 后台「重建索引」按钮走 {@link #fullSyncByTenantId}。不做周期全量同步。
 */
public interface MeiliSearchSyncService {

    /**
     * 重建指定租户的商品索引：从库里读全部商品 upsert 进索引，再删掉库里已不存在的文档。
     *
     * @param tenantId 租户ID
     * @return 写入的商品数量
     */
    int fullSyncByTenantId(String tenantId) throws Exception;

    /**
     * 把单个商品的最新数据写进索引；商品已不存在时删除索引文档。
     *
     * @param tenantId 租户ID
     * @param goodsId  商品 spuId
     */
    void syncOneGoods(String tenantId, String goodsId) throws Exception;

    /**
     * 从索引删除单个商品文档。
     *
     * @param tenantId 租户ID
     * @param goodsId  商品 spuId
     */
    void deleteOneGoods(String tenantId, String goodsId) throws Exception;
}
