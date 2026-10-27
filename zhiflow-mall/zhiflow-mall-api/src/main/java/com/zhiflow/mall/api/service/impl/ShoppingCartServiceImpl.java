/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.incrementer.IdentifierGenerator;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.api.cache.ShoppingCartCache;
import com.zhiflow.mall.api.mapper.GoodsSpuMapper;
import com.zhiflow.mall.api.mapper.ShoppingCartMapper;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShoppingCart;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * 购物车（单店铺，不按店铺分组）。
 *
 * <p>购物车行走物理删（登记在 {@code PhysicalDeleteRegistry}）：没有回收站语义，软删的墓碑行既会让表无限膨胀，
 * 又会占着唯一索引干扰加购合并。MyBatis-Plus 没有「按实体关闭 @TableLogic」的开关，
 * 所以四个删除入口在这里全部覆盖，否则任何未覆盖的入口都会静默变成软删。
 *
 * <p>整份购物车有缓存（{@link ShoppingCartCache}）：每个写入口写库之后都要登记「提交后清该用户」，
 * 契约测试 {@code ShoppingCartCacheEvictContractTest} 守着。
 *
 * @date 2019-08-29 21:27:33
 */
@Service
@AllArgsConstructor
public class ShoppingCartServiceImpl extends ServiceImpl<ShoppingCartMapper, ShoppingCart> implements ShoppingCartService {

    private final GoodsSpuMapper goodsSpuMapper;
    /** 加购 upsert 绕过了 MP 的 insert，主键得自己取；用与 ASSIGN_ID 同一个注册表机器位雪花 */
    private final IdentifierGenerator identifierGenerator;
    private final ShoppingCartCache shoppingCartCache;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean save(ShoppingCart entity) {
        GoodsSpu spu = goodsSpuMapper.selectById(entity.getSpuId());
        if (spu == null || !MallConstants.UP.equals(spu.getShelf())) {
            throw new BizException(MallReturnCode.ERR_80004.getCode(), MallReturnCode.ERR_80004.getMsg());
        }
        // 单条 INSERT ... ON CONFLICT DO UPDATE：并发加购同一 SKU 时两次都成功，数量 = 两次之和
        // （见 ShoppingCartMapper#upsertAddQuantity）。该语句绕过租户拦截器，tenant_id 必须显式给出。
        String tenantId = TenantContextHolder.getTenantId();
        if (StrUtil.isBlank(tenantId)) {
            throw new IllegalStateException("加购缺少租户上下文");
        }
        LocalDateTime now = LocalDateTime.now();
        entity.setId(String.valueOf(identifierGenerator.nextId(entity)));
        entity.setTenantId(tenantId);
        entity.setCreateTime(now);
        entity.setUpdateTime(now);
        ShoppingCart saved = baseMapper.upsertAddQuantity(entity);
        entity.setId(saved.getId());
        entity.setQuantity(saved.getQuantity());
        shoppingCartCache.evictAfterCommit(tenantId, entity.getUserId());
        return true;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean updateById(ShoppingCart entity) {
        ShoppingCart existing = entity.getId() == null ? null : baseMapper.selectById(entity.getId());
        if (existing == null || !Objects.equals(existing.getUserId(), entity.getUserId())) {
            throw new BizException(MallReturnCode.ERR_80004.getCode(), MallReturnCode.ERR_80004.getMsg());
        }
        if (StrUtil.isNotBlank(entity.getSpuId())
                && !Objects.equals(existing.getSpuId(), entity.getSpuId())) {
            throw new BizException(MallReturnCode.ERR_80004.getCode(), MallReturnCode.ERR_80004.getMsg());
        }
        entity.setSpuId(existing.getSpuId());
        entity.setSkuId(StrUtil.blankToDefault(entity.getSkuId(), existing.getSkuId()));
        // 改规格后与另一行同 SKU：合并数量并物理删掉另一行
        ShoppingCart sameSku = baseMapper.selectOne(Wrappers.<ShoppingCart>lambdaQuery()
                .eq(ShoppingCart::getUserId, entity.getUserId())
                .eq(ShoppingCart::getSpuId, entity.getSpuId())
                .eq(ShoppingCart::getSkuId, entity.getSkuId()));
        if (sameSku != null && !entity.getId().equals(sameSku.getId())) {
            entity.setQuantity(entity.getQuantity() + sameSku.getQuantity());
            baseMapper.physicalRemoveById(sameSku.getId());
        }
        boolean updated = super.updateById(entity);
        shoppingCartCache.evictAfterCommit(existing.getTenantId(), existing.getUserId());
        return updated;
    }

    /**
     * 购物车列表。某个用户的整份购物车（不带 shopId 筛选）走缓存，命中时 0 条 SQL；
     * 带筛选参数的照旧查库。两条路返回的内容相同（查库实现见 {@link ShoppingCartCache#load}）。
     */
    @Override
    public List<ShoppingCart> listCart(ShoppingCart shoppingCart) {
        if (ShoppingCartCache.isWholeCart(shoppingCart)) {
            return shoppingCartCache.getCart(shoppingCart.getUserId()).toCarts();
        }
        return ShoppingCartCache.load(baseMapper, shoppingCart);
    }

    /** 一条 COUNT，与 {@link #listCart} 数的是同一批行（筛选口径见 ShoppingCartMapper.xml#countByCondition）。 */
    @Override
    public int countByCondition(ShoppingCart shoppingCart) {
        return baseMapper.countByCondition(shoppingCart);
    }

    /** 先查出行的用户再删（购物车缓存按用户清）。目前没有调用点，用户端删除走 {@link #remove(Wrapper)}。 */
    @Override
    public boolean removeById(java.io.Serializable id) {
        String rowId = String.valueOf(id);
        List<ShoppingCart> owners = ownersOf(List.of(rowId));
        boolean removed = baseMapper.physicalRemoveById(rowId) > 0;
        evictOwners(owners);
        return removed;
    }

    /** 同 {@link #removeById}：先查出这些行的用户再删。 */
    @Override
    public boolean removeByIds(Collection<?> idList) {
        if (CollectionUtil.isNotEmpty(idList)) {
            List<String> ids = idList.stream().map(String::valueOf).toList();
            List<ShoppingCart> owners = ownersOf(ids);
            baseMapper.physicalRemoveByIds(ids);
            evictOwners(owners);
        }
        return true;
    }

    @Override
    public boolean removeBatchByIds(Collection<?> idList) {
        return removeByIds(idList);
    }

    /**
     * 下单成功后清空已购商品走的就是这里。先查 id 再物理删：{@code delete(wrapper)} 在 {@code @TableLogic}
     * 下同样被改写成 UPDATE，没有 wrapper 版的物理删入口。
     */
    @Override
    public boolean remove(Wrapper<ShoppingCart> queryWrapper) {
        List<ShoppingCart> rows = baseMapper.selectList(queryWrapper);
        List<String> ids = rows.stream()
                .map(ShoppingCart::getId)
                .toList();
        if (CollectionUtil.isNotEmpty(ids)) {
            baseMapper.physicalRemoveByIds(ids);
            // 被删行就是刚查出来的那批，直接按它们的用户清，不多一次查询
            evictOwners(rows);
        }
        return true;
    }

    /** 这些行的用户与租户（只取两列）。 */
    private List<ShoppingCart> ownersOf(List<String> ids) {
        return baseMapper.selectList(Wrappers.<ShoppingCart>lambdaQuery()
                .select(ShoppingCart::getUserId, ShoppingCart::getTenantId)
                .in(ShoppingCart::getId, ids));
    }

    /** 每个（租户, 用户）清一次。 */
    private void evictOwners(List<ShoppingCart> rows) {
        Set<Owner> owners = new LinkedHashSet<>();
        rows.forEach(row -> owners.add(new Owner(row.getTenantId(), row.getUserId())));
        owners.forEach(owner -> shoppingCartCache.evictAfterCommit(owner.tenantId(), owner.userId()));
    }

    private record Owner(String tenantId, String userId) {
    }
}
