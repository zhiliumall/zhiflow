/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.mall.common.entity.ShoppingCart;
import com.zhiflow.mall.admin.mapper.ShoppingCartMapper;
import com.zhiflow.mall.admin.service.ShoppingCartService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;

/**
 * 购物车
 *
 * <p>用户端整份购物车有缓存：每个写入口都清受影响用户的缓存（{@link ShoppingCartCacheEviction}，提交后清）。
 * 目前后台没有任何地方调用这些写方法，清缓存是给以后的调用方兜底。
 *
 * @date 2019-08-29 21:27:33
 */
@Service
@RequiredArgsConstructor
public class ShoppingCartServiceImpl extends ServiceImpl<ShoppingCartMapper, ShoppingCart> implements ShoppingCartService {

	private final ShoppingCartCacheEviction shoppingCartCacheEviction;

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean save(ShoppingCart entity) {
		ShoppingCart shoppingCart = baseMapper.selectOne(Wrappers.<ShoppingCart>lambdaQuery()
				.eq(ShoppingCart::getUserId,entity.getUserId())
				.eq(ShoppingCart::getSpuId,entity.getSpuId())
				.eq(ShoppingCart::getSkuId,entity.getSkuId())
				.eq(ShoppingCart::getDelFlag, CommonConstants.STATUS_NORMAL));
		if(shoppingCart != null){
			entity.setQuantity(entity.getQuantity() + shoppingCart.getQuantity());
			// 物理删：软删的墓碑行会一直占着唯一索引
			// shopping_cart_tenant_id_user_id_spu_id_sku_id_idx，下一行 save 必然 duplicate key
			baseMapper.physicalRemoveById(shoppingCart.getId());
		}
		boolean saved = super.save(entity);
		shoppingCartCacheEviction.evictCart(entity.getTenantId(), entity.getUserId());
		return saved;
	}

	@Override
	@Transactional(rollbackFor = Exception.class)
	public boolean updateById(ShoppingCart entity) {
		// 改之前这一行属于谁（userId 可能被改掉，新旧两个用户都要清）
		shoppingCartCacheEviction.evictCartsOfRows(Collections.singletonList(entity.getId()));
		ShoppingCart shoppingCart = baseMapper.selectOne(Wrappers.<ShoppingCart>lambdaQuery()
				.eq(ShoppingCart::getUserId,entity.getUserId())
				.eq(ShoppingCart::getSpuId,entity.getSpuId())
				.eq(ShoppingCart::getSkuId,entity.getSkuId())
				.eq(ShoppingCart::getDelFlag, CommonConstants.STATUS_NORMAL));
		if(shoppingCart != null && !entity.getId().equals(shoppingCart.getId())){
			entity.setQuantity(entity.getQuantity() + shoppingCart.getQuantity());
			// 物理删，理由同 save
			baseMapper.physicalRemoveById(shoppingCart.getId());
		}
		boolean updated = super.updateById(entity);
		shoppingCartCacheEviction.evictCart(entity.getTenantId(), entity.getUserId());
		return updated;
	}

	/**
	 * 后台「通过 id 删除购物车」走物理删。
	 *
	 * <p>购物车登记在 {@code PhysicalDeleteRegistry}：没有回收站语义，软删只会让表膨胀，
	 * 且墓碑行会干扰加购合并。覆盖 {@code IService#removeById} 而不是改 Controller，
	 * 是为了让这个实体的所有删除入口只有一种语义。
	 */
	@Override
	public boolean removeById(java.io.Serializable id) {
		// 删之前查出这一行的用户（删了就查不到了）
		shoppingCartCacheEviction.evictCartsOfRows(List.of(String.valueOf(id)));
		return baseMapper.physicalRemoveById(String.valueOf(id)) > 0;
	}


    // ---------- S8 波次 4（D3）：物理删实体的四个删除入口必须全部覆盖 ----------
    // ShoppingCart 登记在 PhysicalDeleteRegistry（理由见 ShoppingCartMapper#physicalRemoveById）。
    // MyBatis-Plus 没有「按实体关闭 @TableLogic」的开关，任何未覆盖的入口翻转后都会
    // 静默变成软删；对主键即业务键的实体，墓碑还会让「删后重插」直接主键冲突。

    @Override
    public boolean removeByIds(java.util.Collection<?> idList) {
        if (idList == null || idList.isEmpty()) {
            return true;
        }
        List<String> ids = idList.stream().map(String::valueOf).toList();
        shoppingCartCacheEviction.evictCartsOfRows(ids);
        return baseMapper.physicalRemoveByIds(ids) > 0;
    }

    @Override
    public boolean removeBatchByIds(java.util.Collection<?> idList) {
        return removeByIds(idList);
    }

    /**
     * <b>有意的守卫，不是未完成的实现。</b>本入口今天零调用点，也没有现成的批量裸删可一行委托。
     * 留空会让它在翻转后静默走软删（ShoppingCart 是物理删实体，那等于悄悄改变语义）；
     * fail-fast 则让将来第一个误用的人当场看到，而不是事后从数据里发现。
     */
    @Override
    public boolean remove(com.baomidou.mybatisplus.core.conditions.Wrapper<ShoppingCart> queryWrapper) {
        throw new UnsupportedOperationException(
                "ShoppingCart 为物理删实体（见 PhysicalDeleteRegistry），此入口未接物理删实现："
                        + "请用 physicalRemove* 或 removeById");
    }
}
