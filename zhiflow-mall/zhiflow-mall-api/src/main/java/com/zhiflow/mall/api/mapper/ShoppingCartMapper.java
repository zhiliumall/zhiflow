/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.entity.ShoppingCart;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 购物车
 *
 * @date 2019-08-29 21:27:33
 */
public interface ShoppingCartMapper extends BaseMapper<ShoppingCart> {

	/**
	 * {@link #upsertAddQuantity} 的 SQL。单独成常量是为了让真库并发测试跑的就是这一句，而不是另抄一份。
	 */
	String UPSERT_ADD_QUANTITY_SQL = "INSERT INTO shopping_cart (id, tenant_id, shop_id, del_flag, create_time, update_time,"
			+ " user_id, spu_id, sku_id, quantity, add_price, spu_name, spec_info, pic_url, page_type)"
			+ " VALUES (#{c.id}, #{c.tenantId}, #{c.shopId}, '0', #{c.createTime}, #{c.updateTime},"
			+ " #{c.userId}, #{c.spuId}, #{c.skuId}, #{c.quantity}, #{c.addPrice}, #{c.spuName}, #{c.specInfo}, #{c.picUrl},"
			+ " #{c.pageType})"
			+ " ON CONFLICT (tenant_id, user_id, spu_id, sku_id) WHERE del_flag = '0' DO UPDATE SET"
			+ " quantity = shopping_cart.quantity + EXCLUDED.quantity,"
			+ " shop_id = EXCLUDED.shop_id, add_price = EXCLUDED.add_price, spu_name = EXCLUDED.spu_name,"
			+ " spec_info = EXCLUDED.spec_info, pic_url = EXCLUDED.pic_url, page_type = EXCLUDED.page_type,"
			+ " create_time = EXCLUDED.create_time, update_time = EXCLUDED.update_time"
			+ " RETURNING id, quantity";

	/**
	 * 加购：同一 (tenant, user, spu, sku) 已有活跃行就把数量累加上去，没有就插一行——一条语句完成。
	 *
	 * <p>原来是「查旧行 → 物理删 → 插新行」三步、无锁：同一用户同一 SKU 两个加购并发时，两边都查到「没有」
	 * （或都删同一行），第二个插入撞部分唯一索引 {@code shopping_cart_tenant_id_user_id_spu_id_sku_id_idx}
	 * 直接 500，另一种交错则丢掉一次的数量。{@code ON CONFLICT ... DO UPDATE} 由 PG 在唯一索引上串行化：
	 * 两次都成功，最终数量 = 两次之和。
	 *
	 * <ul>
	 *   <li>冲突分支除数量外，其余快照列（店铺、加入时价格 / 名称 / 规格 / 图片、页面来源、创建 / 更新时间）
	 *       一律取本次请求的值——与原来「删旧行、插新行」的结果逐列一致；只有行 id 保持不变（原来每次换新 id）。</li>
	 *   <li>{@code WHERE del_flag = '0'} 是<b>索引推断谓词</b>，不是过滤条件：该唯一索引是部分索引，
	 *       不写谓词 PG 推断不到，报 42P10。</li>
	 *   <li>{@code @InterceptorIgnore}：租户拦截器会用 JSqlParser 重写 INSERT 补 tenant_id，跳过它规避 PG
	 *       {@code ON CONFLICT} 的解析不确定性；tenant_id 由调用方按租户上下文显式传入（原来也是这个值）。
	 *       shopping_cart 不在分表名单里，跳过动态表名同样无副作用。</li>
	 *   <li>时间由应用传入而不是库里 {@code now()}：库跑 UTC、应用写 +8 墙钟，混用会让购物车按 update_time 的排序错位 8 小时。</li>
	 *   <li>{@code RETURNING} 要走 {@code @Select} 才拿得到结果行；{@code flushCache} 防一级缓存把第二次同参调用
	 *       直接吃掉（同 {@code StockHoldMapper#releaseHolds}）。</li>
	 * </ul>
	 *
	 * @return 落库后的行 id 与累加后的数量
	 */
	@InterceptorIgnore(tenantLine = "true", dynamicTableName = "true")
	@Select(UPSERT_ADD_QUANTITY_SQL)
	@Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
	ShoppingCart upsertAddQuantity(@Param("c") ShoppingCart cart);

	/**
	 * 迁移用户购物车数据（用于账号合并）。
	 * <p>⚠️ 活跃行在 (tenant_id, user_id, spu_id, sku_id) 上有部分唯一索引，两边同时
	 * 加购了同一 SKU 时平移必撞。调用前先 {@link #mergeQuantityIntoTarget} +
	 * {@link #physicalRemoveMergedSourceRows} 清掉冲突行。
	 * @param oldUserId 原用户ID
	 * @param newUserId 新用户ID
	 * @return 更新记录数
	 */
	@org.apache.ibatis.annotations.Update("UPDATE shopping_cart SET user_id = #{newUserId} WHERE user_id = #{oldUserId}")
	int updateUserId(@Param("oldUserId") String oldUserId, @Param("newUserId") String newUserId);

	/**
	 * 账号合并：目标账号购物车里已有同一 (tenant_id, spu_id, sku_id) 活跃行时，
	 * 把源行数量累加进目标行（平移会撞 shopping_cart_tenant_id_user_id_spu_id_sku_id_idx）。
	 * @return 被并入的目标行数
	 */
	@org.apache.ibatis.annotations.Update("UPDATE shopping_cart SET "
			+ "quantity = quantity + (SELECT s.quantity FROM shopping_cart s WHERE s.user_id = #{oldUserId} AND s.del_flag = '0' "
			+ "AND s.tenant_id = shopping_cart.tenant_id AND s.spu_id = shopping_cart.spu_id AND s.sku_id = shopping_cart.sku_id), "
			+ "update_time = now() "
			+ "WHERE user_id = #{newUserId} AND del_flag = '0' "
			+ "AND EXISTS (SELECT 1 FROM shopping_cart s WHERE s.user_id = #{oldUserId} AND s.del_flag = '0' "
			+ "AND s.tenant_id = shopping_cart.tenant_id AND s.spu_id = shopping_cart.spu_id AND s.sku_id = shopping_cart.sku_id)")
	int mergeQuantityIntoTarget(@Param("oldUserId") String oldUserId, @Param("newUserId") String newUserId);

	/**
	 * 账号合并：物理删除数量已并入目标行的源行（同 (tenant,spu,sku) 的活跃行），
	 * 语义同 {@link #physicalRemoveById}（ShoppingCart 已登记 PhysicalDeleteRegistry）。
	 */
	@org.apache.ibatis.annotations.Delete("DELETE FROM shopping_cart WHERE user_id = #{oldUserId} AND del_flag = '0' "
			+ "AND EXISTS (SELECT 1 FROM shopping_cart t WHERE t.user_id = #{newUserId} AND t.del_flag = '0' "
			+ "AND t.tenant_id = shopping_cart.tenant_id AND t.spu_id = shopping_cart.spu_id AND t.sku_id = shopping_cart.sku_id)")
	int physicalRemoveMergedSourceRows(@Param("oldUserId") String oldUserId, @Param("newUserId") String newUserId);

	/**
	 * 查询购物车列表（性能优化版本）
	 * 使用 LEFT JOIN 在 SQL 层面过滤无效数据，避免内存过滤
	 * @param shoppingCart 查询条件
	 * @return 购物车列表
	 */
	List<ShoppingCart> selectList2Optimized(@Param("query") ShoppingCart shoppingCart);

	/**
	 * 统计购物车数量（性能优化：仅查询主表，不加载关联数据）
	 * @param shoppingCart 查询条件
	 * @return 数量
	 */
	int countByCondition(@Param("query") ShoppingCart shoppingCart);

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
	@org.apache.ibatis.annotations.Delete("delete from shopping_cart where id = #{id}")
	int physicalRemoveById(@Param("id") String id);

	/** 物理删除多行，语义同 {@link #physicalRemoveById}。空集合由调用方负责短路。 */
	@org.apache.ibatis.annotations.Delete("""
			<script>
			delete from shopping_cart where id in
			<foreach collection="ids" item="id" open="(" separator="," close=")">#{id}</foreach>
			</script>""")
	int physicalRemoveByIds(@Param("ids") java.util.Collection<String> ids);
}
