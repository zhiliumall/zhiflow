/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShopInfo;
import com.zhiflow.mall.common.entity.ShoppingCart;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 一个用户的整份购物车，存在 {@code mall:shopping_cart#600} 里（key 是 userId，读方见 {@link ShoppingCartCache}）。
 *
 * <p><b>为什么不直接缓存 {@link ShoppingCart}</b>：它嵌着 {@link GoodsSpu} / {@link GoodsSku}，两者的 id 带
 * {@code @HashidsId}，缓存序列化器同样认这个注解，编不编码取决于当前线程（{@code HashidsScope}）；
 * 时间字段还带秒级 {@code @JsonFormat}。快照一律存原始值，读出后经 {@link #toCarts} 还原成实体，
 * 由响应序列化按请求编码，对外 JSON 与查库时一致。
 *
 * <p><b>存哪些字段</b>：购物车行与商品、规格、店铺四个实体的<b>全部标量字段</b>，包括 {@code exist = false} 的——
 * 列表 SQL 的 resultMap 实际填了哪些、填的是哪一列不好逐个推（例如同名列取第一列：商品名其实是购物车行加购时的
 * {@code spu_name}；{@code page_type} 查了却不会填进购物车行），全存就不用推，原样还原即可。不存的只有：
 * <ul>
 * <li>SKU 成本价——用户端不能看，查询不取、列表返回前还要再置空一次，还原后恒为 null，与原来一致；</li>
 * <li>这条查询不可能填的嵌套对象：{@code GoodsSpu.skus / spuSpec / goodsBrand}、{@code GoodsSku.specs / goodsSpu}、
 *     {@code ShoppingCart.specs}，还原后为 null，与原来一致。</li>
 * </ul>
 * 实体以后加了标量字段而这里没加，往返测试 {@code ShoppingCartSnapshotTest} 会红。值的结构变了要换缓存名。
 *
 * <p>不进进程内 L1；{@link #toCarts} 每次新建实体，调用方怎么改都不影响缓存里的值。
 */
@Data
public class ShoppingCartSnapshot implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	private List<Row> rows;

	public static ShoppingCartSnapshot of(List<ShoppingCart> carts) {
		ShoppingCartSnapshot snapshot = new ShoppingCartSnapshot();
		List<Row> rows = new ArrayList<>(carts.size());
		carts.forEach(cart -> rows.add(Row.of(cart)));
		snapshot.setRows(rows);
		return snapshot;
	}

	/** 还原成列表接口原来的返回值；每次都是新对象。 */
	public List<ShoppingCart> toCarts() {
		List<ShoppingCart> carts = new ArrayList<>(rows == null ? 0 : rows.size());
		if (rows != null) {
			rows.forEach(row -> carts.add(row.toCart()));
		}
		return carts;
	}

	/** 购物车一行。 */
	@Data
	public static class Row implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String tenantId;
		private String delFlag;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private String shopId;
		private String userId;
		private String spuId;
		private String skuId;
		private Integer quantity;
		private BigDecimal addPrice;
		private String spuName;
		private String pageType;
		private String specInfo;
		private String picUrl;
		private Spu goodsSpu;
		private Sku goodsSku;
		private Shop shopInfo;

		static Row of(ShoppingCart cart) {
			Row row = new Row();
			row.setId(cart.getId());
			row.setTenantId(cart.getTenantId());
			row.setDelFlag(cart.getDelFlag());
			row.setCreateTime(cart.getCreateTime());
			row.setUpdateTime(cart.getUpdateTime());
			row.setShopId(cart.getShopId());
			row.setUserId(cart.getUserId());
			row.setSpuId(cart.getSpuId());
			row.setSkuId(cart.getSkuId());
			row.setQuantity(cart.getQuantity());
			row.setAddPrice(cart.getAddPrice());
			row.setSpuName(cart.getSpuName());
			row.setPageType(cart.getPageType());
			row.setSpecInfo(cart.getSpecInfo());
			row.setPicUrl(cart.getPicUrl());
			row.setGoodsSpu(cart.getGoodsSpu() == null ? null : Spu.of(cart.getGoodsSpu()));
			row.setGoodsSku(cart.getGoodsSku() == null ? null : Sku.of(cart.getGoodsSku()));
			row.setShopInfo(cart.getShopInfo() == null ? null : Shop.of(cart.getShopInfo()));
			return row;
		}

		ShoppingCart toCart() {
			ShoppingCart cart = new ShoppingCart();
			cart.setId(id);
			cart.setTenantId(tenantId);
			cart.setDelFlag(delFlag);
			cart.setCreateTime(createTime);
			cart.setUpdateTime(updateTime);
			cart.setShopId(shopId);
			cart.setUserId(userId);
			cart.setSpuId(spuId);
			cart.setSkuId(skuId);
			cart.setQuantity(quantity);
			cart.setAddPrice(addPrice);
			cart.setSpuName(spuName);
			cart.setPageType(pageType);
			cart.setSpecInfo(specInfo);
			cart.setPicUrl(picUrl);
			cart.setGoodsSpu(goodsSpu == null ? null : goodsSpu.toEntity());
			cart.setGoodsSku(goodsSku == null ? null : goodsSku.toEntity());
			cart.setShopInfo(shopInfo == null ? null : shopInfo.toEntity());
			return cart;
		}
	}

	/** 商品的全部标量字段（id 存原始雪花 id）。 */
	@Data
	public static class Spu implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String tenantId;
		private String delFlag;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private String shopId;
		private String spuCode;
		private String name;
		private String sellPoint;
		private String description;
		private String categoryFirst;
		private String categorySecond;
		private String categoryThird;
		private String videoUrl;
		private List<String> picUrls;
		private String shelf;
		private Integer sort;
		private BigDecimal priceDown;
		private BigDecimal priceUp;
		private Integer saleNum;
		private String specType;
		private String freightType;
		private BigDecimal freightAmount;
		private String verifyStatus;
		private String brandId;
		private Integer viewNum;
		private Integer stockWarnLine;
		private Integer stock;
		private String picUrl;
		private BigDecimal salesPrice;
		private List<String> categoryId;
		private Boolean stockWarning;
		private List<String> label;
		private List<String> platform;
		private String parameterJson;
		private String specSummary;

		static Spu of(GoodsSpu spu) {
			Spu s = new Spu();
			s.setId(spu.getId());
			s.setTenantId(spu.getTenantId());
			s.setDelFlag(spu.getDelFlag());
			s.setCreateTime(spu.getCreateTime());
			s.setUpdateTime(spu.getUpdateTime());
			s.setShopId(spu.getShopId());
			s.setSpuCode(spu.getSpuCode());
			s.setName(spu.getName());
			s.setSellPoint(spu.getSellPoint());
			s.setDescription(spu.getDescription());
			s.setCategoryFirst(spu.getCategoryFirst());
			s.setCategorySecond(spu.getCategorySecond());
			s.setCategoryThird(spu.getCategoryThird());
			s.setVideoUrl(spu.getVideoUrl());
			s.setPicUrls(toList(spu.getPicUrls()));
			s.setShelf(spu.getShelf());
			s.setSort(spu.getSort());
			s.setPriceDown(spu.getPriceDown());
			s.setPriceUp(spu.getPriceUp());
			s.setSaleNum(spu.getSaleNum());
			s.setSpecType(spu.getSpecType());
			s.setFreightType(spu.getFreightType());
			s.setFreightAmount(spu.getFreightAmount());
			s.setVerifyStatus(spu.getVerifyStatus());
			s.setBrandId(spu.getBrandId());
			s.setViewNum(spu.getViewNum());
			s.setStockWarnLine(spu.getStockWarnLine());
			s.setStock(spu.getStock());
			s.setPicUrl(spu.getPicUrl());
			s.setSalesPrice(spu.getSalesPrice());
			s.setCategoryId(spu.getCategoryId() == null ? null : new ArrayList<>(spu.getCategoryId()));
			s.setStockWarning(spu.getStockWarning());
			s.setLabel(toList(spu.getLabel()));
			s.setPlatform(toList(spu.getPlatform()));
			s.setParameterJson(spu.getParameterJson());
			s.setSpecSummary(spu.getSpecSummary());
			return s;
		}

		GoodsSpu toEntity() {
			GoodsSpu spu = new GoodsSpu();
			spu.setId(id);
			spu.setTenantId(tenantId);
			spu.setDelFlag(delFlag);
			spu.setCreateTime(createTime);
			spu.setUpdateTime(updateTime);
			spu.setShopId(shopId);
			spu.setSpuCode(spuCode);
			spu.setName(name);
			spu.setSellPoint(sellPoint);
			spu.setDescription(description);
			spu.setCategoryFirst(categoryFirst);
			spu.setCategorySecond(categorySecond);
			spu.setCategoryThird(categoryThird);
			spu.setVideoUrl(videoUrl);
			spu.setPicUrls(toArray(picUrls));
			spu.setShelf(shelf);
			spu.setSort(sort);
			spu.setPriceDown(priceDown);
			spu.setPriceUp(priceUp);
			spu.setSaleNum(saleNum);
			spu.setSpecType(specType);
			spu.setFreightType(freightType);
			spu.setFreightAmount(freightAmount);
			spu.setVerifyStatus(verifyStatus);
			spu.setBrandId(brandId);
			spu.setViewNum(viewNum);
			spu.setStockWarnLine(stockWarnLine);
			spu.setStock(stock);
			spu.setPicUrl(picUrl);
			spu.setSalesPrice(salesPrice);
			spu.setCategoryId(categoryId == null ? null : new ArrayList<>(categoryId));
			spu.setStockWarning(stockWarning);
			spu.setLabel(toArray(label));
			spu.setPlatform(toArray(platform));
			spu.setParameterJson(parameterJson);
			spu.setSpecSummary(specSummary);
			return spu;
		}
	}

	/** 规格（SKU）的全部标量字段，成本价除外（id、spuId 存原始雪花 id）。 */
	@Data
	public static class Sku implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String tenantId;
		private String delFlag;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private String shopId;
		private String skuCode;
		private String spuId;
		private String picUrl;
		private BigDecimal salesPrice;
		private BigDecimal marketPrice;
		private Integer stock;
		private BigDecimal weight;
		private BigDecimal volume;
		private String enable;
		private Integer version;
		private String name;

		static Sku of(GoodsSku sku) {
			Sku s = new Sku();
			s.setId(sku.getId());
			s.setTenantId(sku.getTenantId());
			s.setDelFlag(sku.getDelFlag());
			s.setCreateTime(sku.getCreateTime());
			s.setUpdateTime(sku.getUpdateTime());
			s.setShopId(sku.getShopId());
			s.setSkuCode(sku.getSkuCode());
			s.setSpuId(sku.getSpuId());
			s.setPicUrl(sku.getPicUrl());
			s.setSalesPrice(sku.getSalesPrice());
			s.setMarketPrice(sku.getMarketPrice());
			s.setStock(sku.getStock());
			s.setWeight(sku.getWeight());
			s.setVolume(sku.getVolume());
			s.setEnable(sku.getEnable());
			s.setVersion(sku.getVersion());
			s.setName(sku.getName());
			return s;
		}

		GoodsSku toEntity() {
			GoodsSku sku = new GoodsSku();
			sku.setId(id);
			sku.setTenantId(tenantId);
			sku.setDelFlag(delFlag);
			sku.setCreateTime(createTime);
			sku.setUpdateTime(updateTime);
			sku.setShopId(shopId);
			sku.setSkuCode(skuCode);
			sku.setSpuId(spuId);
			sku.setPicUrl(picUrl);
			sku.setSalesPrice(salesPrice);
			sku.setMarketPrice(marketPrice);
			sku.setStock(stock);
			sku.setWeight(weight);
			sku.setVolume(volume);
			sku.setEnable(enable);
			sku.setVersion(version);
			sku.setName(name);
			return sku;
		}
	}

	/** 店铺的全部标量字段。 */
	@Data
	public static class Shop implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String tenantId;
		private String delFlag;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private String enable;
		private String phone;
		private String name;
		private String imgUrl;

		static Shop of(ShopInfo shop) {
			Shop s = new Shop();
			s.setId(shop.getId());
			s.setTenantId(shop.getTenantId());
			s.setDelFlag(shop.getDelFlag());
			s.setCreateTime(shop.getCreateTime());
			s.setUpdateTime(shop.getUpdateTime());
			s.setEnable(shop.getEnable());
			s.setPhone(shop.getPhone());
			s.setName(shop.getName());
			s.setImgUrl(shop.getImgUrl());
			return s;
		}

		ShopInfo toEntity() {
			ShopInfo shop = new ShopInfo();
			shop.setId(id);
			shop.setTenantId(tenantId);
			shop.setDelFlag(delFlag);
			shop.setCreateTime(createTime);
			shop.setUpdateTime(updateTime);
			shop.setEnable(enable);
			shop.setPhone(phone);
			shop.setName(name);
			shop.setImgUrl(imgUrl);
			return shop;
		}
	}

	private static List<String> toList(String[] values) {
		return values == null ? null : new ArrayList<>(Arrays.asList(values));
	}

	private static String[] toArray(List<String> values) {
		return values == null ? null : values.toArray(new String[0]);
	}
}
