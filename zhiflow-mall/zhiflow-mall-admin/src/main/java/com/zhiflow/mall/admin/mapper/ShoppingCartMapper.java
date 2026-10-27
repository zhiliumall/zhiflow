/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.apache.ibatis.annotations.Delete;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Collection;
import java.util.List;

/**
 * 购物车
 *
 * @date 2019-08-29 21:27:33
 */
public interface ShoppingCartMapper extends BaseMapper<ShoppingCart> {

	/**
	 * 物理删除单行。
	 *
	 * <p>购物车是 {@code PhysicalDeleteRegistry} 里登记的物理删实体：加购合并会「删旧行 →
	 * 立刻插同 (tenant,user,spu,sku) 的新行」，软删留下的墓碑行会一直占着自然键；
	 * 而且购物车没有回收站语义，墓碑只会让表无限膨胀。
	 *
	 * <p>{@code BaseEntity.delFlag} 上的 {@code @TableLogic} 会把 MyBatis-Plus 的
	 * {@code deleteById}/{@code removeByIds} 改写成 UPDATE，且没有「按实体关闭」的开关，
	 * 所以这里走裸 SQL 绕开改写。方法名前缀 {@code physicalRemove} 是门禁约定的显式意图声明。
	 */
	@Delete("delete from shopping_cart where id = #{id}")
	int physicalRemoveById(@Param("id") String id);

	/** 物理删除多行，语义同 {@link #physicalRemoveById}。空集合由调用方负责短路。 */
	@Delete("""
			<script>
			delete from shopping_cart where id in
			<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
			</script>""")
	int physicalRemoveByIds(@Param("ids") Collection<String> ids);

	/**
	 * 购物车里有这些商品的用户（每个用户、租户一行，只填 userId、tenantId）。后台改商品后按用户清购物车缓存用，
	 * 见 {@code ShoppingCartCacheEviction}。空集合由调用方负责短路。
	 */
	@Select("""
			<script>
			select distinct user_id, tenant_id from shopping_cart where del_flag = '0' and spu_id in
			<foreach collection="spuIds" item="id" open="(" separator="," close=")">#{id}</foreach>
			</script>""")
	List<ShoppingCart> selectOwnersBySpuIds(@Param("spuIds") Collection<String> spuIds);

	/** 购物车里有这个店铺商品的用户，同 {@link #selectOwnersBySpuIds}。 */
	@Select("select distinct user_id, tenant_id from shopping_cart where del_flag = '0' and shop_id = #{shopId}")
	List<ShoppingCart> selectOwnersByShopId(@Param("shopId") String shopId);

	/** 这些购物车行的用户，同 {@link #selectOwnersBySpuIds}。空集合由调用方负责短路。 */
	@Select("""
			<script>
			select distinct user_id, tenant_id from shopping_cart where id in
			<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
			</script>""")
	List<ShoppingCart> selectOwnersByIds(@Param("ids") Collection<String> ids);

}
