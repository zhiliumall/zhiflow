/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 商品sku
 *
 * @date 2019-08-13 10:18:34
 */
public interface GoodsSkuMapper extends BaseMapper<GoodsSku> {

	/** 商品详情的在售 SKU，不含成本价、库存、版本号。 */
	List<GoodsSku> listDetailSkuBySpuId(String spuId);

	GoodsSku selectOneByShoppingCart(String id);

	List<GoodsSku> listSkuInOrderSub(@Param("shopId")String shopId ,@Param("listPlaceOrderSkuDTO")List<PlaceOrderSkuDTO> listPlaceOrderSkuDTO);

	/**
	 * 原子扣减库存：{@code stock = stock - #{delta}, version = version + 1 WHERE id = #{skuId} AND stock >= #{delta}}。
	 * 替代下单事务里「实体 setStock + updateById 读改写」的 per-item 全字段 UPDATE，
	 * 单线程等价、并发下扣减不再互相丢失。{@code stock < delta} 时影响 0 行，调用方必须抛错让
	 * 下单事务回滚；选单时的库存校验只是用户体验优化，不能替代这里的数据库守卫。
	 * version 同步 +1 是为对齐原 updateById 的乐观锁写路径（GoodsSku 有 @Version 且拦截器已启用）：
	 * 并发的取消/后台等携带旧 version 的读改写仍会冲突失败并重读，而非用陈旧 stock 覆盖本次扣减；
	 * version 为 NULL 的行 {@code NULL + 1 = NULL}，与原乐观锁"实体 version 为 null 则跳过"行为一致。
	 * goods_sku 单表无 JOIN，多租户插件注入 tenant_id 无歧义。
	 *
	 * <p><b>RETURNING stock</b>（2026-10-23 售罄即时清）：同一条语句带回扣后剩余库存，0 次额外往返；
	 * 调用方据此判断是否售罄 / 低库存，提交后清详情的库存缓存（见 {@code OrderStockDeduction}）。
	 * MyBatis 的 {@code @Update} 拿不到结果集，所以声明成 {@code @Select}：租户行拦截器在查询路径同样按语句类型
	 * （JSqlParser 解析出 Update）分派到 processUpdate 注入 tenant_id，SchemaInterceptor 在 beforePrepare 两种都跑
	 * （真 PG 验证见 OrderStockDeductionPgTest）。flushCache：它是带副作用的 SELECT，同一事务里同参数再扣一次
	 * 不能命中 MyBatis 一级缓存而不发语句。
	 *
	 * @return 扣后剩余库存；没有扣到（库存不足或 SKU 行不存在）返回 null，调用方必须抛错让下单事务回滚
	 */
	// TOCTOU 超卖修复：WHERE 加 stock >= delta 非负守卫，库存不足则影响 0 行（调用方按 null 抛错回滚），
	// 不再把共享 stock 扣成负数（尤其秒杀 Redis 桶容量 > goods_sku.stock 时的异步下单）。
	@Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
	@Select("UPDATE goods_sku SET stock = stock - #{delta}, version = version + 1 WHERE id = #{skuId} AND stock >= #{delta} RETURNING stock")
	Integer decreaseStock(@Param("skuId") String skuId, @Param("delta") Integer delta);

	/**
	 * 原子回补库存（订单取消）：{@code stock = stock + #{delta}, version = version + 1 WHERE id = #{skuId}}。
	 * 替代 doOrderCancel（V1 卖家/服务间取消）里「selectById + setStock + updateById 读改写」的
	 * per-item 全字段 UPDATE：单线程等价，并发下回补不再互相丢失。回补方向是加库存，无"到负"问题。
	 * version bump / NULL version / 多租户注入的理由与 {@link #decreaseStock} 完全一致。
	 * 返回 0 行只剩「SKU 行不存在」一因（原子 SQL 无乐观锁版本冲突失败模式），
	 * 调用方对齐原 {@code if (goodsSku != null)} 的"SKU 已删则静默跳过"语义。
	 */
	@Update("UPDATE goods_sku SET stock = stock + #{delta}, version = version + 1 WHERE id = #{skuId}")
	int increaseStock(@Param("skuId") String skuId, @Param("delta") Integer delta);

	/**
	 * 本事务内的等锁上限（{@code set_config(..., is_local = true)} 即 {@code SET LOCAL}）：只作用到当前事务结束，
	 * 连接归还 / 事务回滚后自动失效，不会带到下一次借出。用 set_config 而不是 {@code SET LOCAL lock_timeout = ?}，
	 * 是因为 SET 语句不能带绑定参数。下单扣库存专用，见 {@code OrderStockDeduction}。
	 * 不涉及业务表：跳过租户行与动态表名拦截（不必让 JSqlParser 解析）。
	 * flushCache：它是带副作用的 SELECT，同一事务（同一 SqlSession）里同参数再调一次时不能命中 MyBatis 一级缓存而不发语句。
	 *
	 * @param lockTimeout PG 时长文本，如 {@code 2000ms}
	 * @return 设置后的值
	 */
	@InterceptorIgnore(tenantLine = "true", dynamicTableName = "true")
	@Options(flushCache = Options.FlushCachePolicy.TRUE, useCache = false)
	@Select("SELECT set_config('lock_timeout', #{lockTimeout}, true)")
	String setLocalLockTimeout(@Param("lockTimeout") String lockTimeout);

	/**
	 * 把本事务的等锁上限恢复成连接默认值（服务端配置 / 连接参数里的值，即 RESET 的目标），撤掉 {@link #setLocalLockTimeout}。
	 * mall-api 别处没有在会话或事务级设置过 lock_timeout，所以「连接默认值」就是进入扣库存之前的值。
	 */
	@InterceptorIgnore(tenantLine = "true", dynamicTableName = "true")
	@Update("SET LOCAL lock_timeout TO DEFAULT")
	int resetLocalLockTimeout();
}
