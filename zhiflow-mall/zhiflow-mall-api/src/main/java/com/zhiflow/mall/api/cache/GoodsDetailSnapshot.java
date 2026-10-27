/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.cache;

import com.zhiflow.common.data.cache.L1Weighted;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.vo.GoodsSpecLeafVO;
import com.zhiflow.mall.common.vo.GoodsSpecVO;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 商品详情的静态快照，存在 {@code mall:goods_detail#600} 里；三个用户端接口共用：
 * {@code /goodsspu/detail/init/{id}}、{@code /goodsspu/{id}}、{@code /goodsparameter/spu/{id}}。
 *
 * <p><b>为什么不直接缓存 {@link GoodsSpu} / {@link GoodsSku}</b>：它们的 id 带 {@code @HashidsId}，
 * 缓存序列化器同样认这个注解，而编不编码取决于当前线程（{@code HashidsScope}）——写缓存与读缓存的线程不一致时，
 * 读回来的 id 是 hashid 串，再拿去查库永远查不到。快照类一律存原始雪花 id，读出后再转回响应对象，
 * 由响应序列化按请求编码，对外 JSON 与改动前一致。
 *
 * <p><b>不放的东西</b>：
 * <ul>
 * <li>库存、SKU 版本号、商品销量——每单都变，单独放在 5 秒的 {@link GoodsStockSnapshot}；</li>
 * <li>成本价——用户端不能看，查询里就不取；</li>
 * <li>品牌、店铺——各有各的缓存。</li>
 * </ul>
 *
 * <p><b>只读</b>：默认配置给这份缓存开了进程内 L1，命中时多个请求拿到<b>同一个实例</b>。组装（{@link #of}）之后谁也不能
 * set 它或改它里面的列表；读方一律经 {@link #toGoodsSpu} / {@link #toSpecTree} 转出新对象再改（2026-10-23 审计，
 * 守护测试 GoodsDetailSnapshotReadOnlyTest）。
 */
@Data
public class GoodsDetailSnapshot implements Serializable, L1Weighted {

	@Serial
	private static final long serialVersionUID = 1L;

	private String id;
	private String tenantId;
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
	private LocalDateTime createTime;
	private LocalDateTime updateTime;
	private String specType;
	private String delFlag;
	private String freightType;
	private BigDecimal freightAmount;
	private String verifyStatus;
	private String brandId;
	private List<String> label;
	private String parameterJson;
	private Integer viewNum;
	private List<Sku> skus;
	private List<Spec> specTree;

	/** 在售 SKU，不含库存、版本号、成本价。 */
	@Data
	public static class Sku implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private String tenantId;
		private String shopId;
		private String skuCode;
		private String spuId;
		private String picUrl;
		private BigDecimal salesPrice;
		private BigDecimal marketPrice;
		private BigDecimal weight;
		private BigDecimal volume;
		private String delFlag;
		private String enable;
		private List<SpecValue> specs;

		static Sku of(GoodsSku sku, List<GoodsSkuSpecValue> specs) {
			Sku snapshot = new Sku();
			snapshot.setId(sku.getId());
			snapshot.setCreateTime(sku.getCreateTime());
			snapshot.setUpdateTime(sku.getUpdateTime());
			snapshot.setTenantId(sku.getTenantId());
			snapshot.setShopId(sku.getShopId());
			snapshot.setSkuCode(sku.getSkuCode());
			snapshot.setSpuId(sku.getSpuId());
			snapshot.setPicUrl(sku.getPicUrl());
			snapshot.setSalesPrice(sku.getSalesPrice());
			snapshot.setMarketPrice(sku.getMarketPrice());
			snapshot.setWeight(sku.getWeight());
			snapshot.setVolume(sku.getVolume());
			snapshot.setDelFlag(sku.getDelFlag());
			snapshot.setEnable(sku.getEnable());
			snapshot.setSpecs(specs.stream().map(SpecValue::of).collect(Collectors.toCollection(ArrayList::new)));
			return snapshot;
		}

		/** 库存取不到（库存缓存与快照的 SKU 对不上，只在后台改 SKU 后清缓存失败时出现）按 0 展示。 */
		GoodsSku toGoodsSku(GoodsStockSnapshot.SkuStock stock) {
			GoodsSku sku = new GoodsSku();
			sku.setId(id);
			sku.setCreateTime(createTime);
			sku.setUpdateTime(updateTime);
			sku.setTenantId(tenantId);
			sku.setShopId(shopId);
			sku.setSkuCode(skuCode);
			sku.setSpuId(spuId);
			sku.setPicUrl(picUrl);
			sku.setSalesPrice(salesPrice);
			sku.setMarketPrice(marketPrice);
			sku.setStock(stock == null || stock.getStock() == null ? 0 : stock.getStock());
			sku.setVersion(stock == null ? null : stock.getVersion());
			sku.setWeight(weight);
			sku.setVolume(volume);
			sku.setDelFlag(delFlag);
			sku.setEnable(enable);
			List<GoodsSkuSpecValue> values = new ArrayList<>();
			if (specs != null) {
				specs.forEach(value -> values.add(value.toEntity()));
			}
			sku.setSpecs(values);
			return sku;
		}
	}

	/** SKU 的一个规格值（含规格 id 与规格值名）。 */
	@Data
	public static class SpecValue implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String tenantId;
		private String spuId;
		private String skuId;
		private String specValueId;
		private LocalDateTime createTime;
		private LocalDateTime updateTime;
		private Integer sort;
		private String specId;
		private String specValueName;

		static SpecValue of(GoodsSkuSpecValue value) {
			SpecValue snapshot = new SpecValue();
			snapshot.setId(value.getId());
			snapshot.setTenantId(value.getTenantId());
			snapshot.setSpuId(value.getSpuId());
			snapshot.setSkuId(value.getSkuId());
			snapshot.setSpecValueId(value.getSpecValueId());
			snapshot.setCreateTime(value.getCreateTime());
			snapshot.setUpdateTime(value.getUpdateTime());
			snapshot.setSort(value.getSort());
			snapshot.setSpecId(value.getSpecId());
			snapshot.setSpecValueName(value.getSpecValueName());
			return snapshot;
		}

		GoodsSkuSpecValue toEntity() {
			GoodsSkuSpecValue entity = new GoodsSkuSpecValue();
			entity.setId(id);
			entity.setTenantId(tenantId);
			entity.setSpuId(spuId);
			entity.setSkuId(skuId);
			entity.setSpecValueId(specValueId);
			entity.setCreateTime(createTime);
			entity.setUpdateTime(updateTime);
			entity.setSort(sort);
			entity.setSpecId(specId);
			entity.setSpecValueName(specValueName);
			return entity;
		}
	}

	/** 规格树的一个维度。 */
	@Data
	public static class Spec implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String spuId;
		private String value;
		private List<Leaf> leaf;

		static Spec of(GoodsSpecVO vo) {
			Spec snapshot = new Spec();
			snapshot.setId(vo.getId());
			snapshot.setSpuId(vo.getSpuId());
			snapshot.setValue(vo.getValue());
			snapshot.setLeaf(vo.getLeaf() == null ? null
					: vo.getLeaf().stream().map(Leaf::of).collect(Collectors.toCollection(ArrayList::new)));
			return snapshot;
		}

		GoodsSpecVO toVO() {
			GoodsSpecVO vo = new GoodsSpecVO();
			vo.setId(id);
			vo.setSpuId(spuId);
			vo.setValue(value);
			vo.setLeaf(leaf == null ? null : leaf.stream().map(Leaf::toVO).collect(Collectors.toCollection(ArrayList::new)));
			return vo;
		}
	}

	/** 规格树里某个维度下的一个取值。 */
	@Data
	public static class Leaf implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		private String id;
		private String value;

		static Leaf of(GoodsSpecLeafVO vo) {
			Leaf snapshot = new Leaf();
			snapshot.setId(vo.getId());
			snapshot.setValue(vo.getValue());
			return snapshot;
		}

		GoodsSpecLeafVO toVO() {
			GoodsSpecLeafVO vo = new GoodsSpecLeafVO();
			vo.setId(id);
			vo.setValue(value);
			return vo;
		}
	}

	/** 对象头 + 引用字段的粗略开销（字节）。 */
	private static final int OBJECT_BYTES = 64;

	/**
	 * 进程内 L1 的权重：近似常驻 KB。字符串按 2 字节 / 字符（含中文时 JDK 紧凑字符串退回 UTF-16）+ 对象开销估，宁大勿小。
	 * 方法名不是 getter：Jackson 不会把它当字段写进 Redis。
	 */
	@Override
	public int l1WeightKb() {
		long bytes = OBJECT_BYTES + 30L * 8
				+ chars(id, tenantId, shopId, spuCode, name, sellPoint, description, categoryFirst, categorySecond,
						categoryThird, videoUrl, shelf, specType, delFlag, freightType, verifyStatus, brandId, parameterJson)
				+ list(picUrls) + list(label) + 3 * 40 + 2 * 48;
		if (skus != null) {
			for (Sku sku : skus) {
				bytes += OBJECT_BYTES + 16L * 8 + 4 * 40 + 2 * 48
						+ chars(sku.id, sku.tenantId, sku.shopId, sku.skuCode, sku.spuId, sku.picUrl, sku.delFlag, sku.enable);
				if (sku.specs != null) {
					bytes += OBJECT_BYTES;
					for (SpecValue value : sku.specs) {
						bytes += OBJECT_BYTES + 10L * 8 + 2 * 48 + chars(value.id, value.tenantId, value.spuId, value.skuId,
								value.specValueId, value.specId, value.specValueName);
					}
				}
			}
		}
		if (specTree != null) {
			for (Spec spec : specTree) {
				bytes += 2L * OBJECT_BYTES + chars(spec.id, spec.spuId, spec.value);
				if (spec.leaf != null) {
					for (Leaf leaf : spec.leaf) {
						bytes += OBJECT_BYTES + chars(leaf.id, leaf.value);
					}
				}
			}
		}
		return (int) Math.max(1, (bytes + 1023) / 1024);
	}

	private static long chars(String... values) {
		long bytes = 0;
		for (String value : values) {
			if (value != null) {
				bytes += 48 + 2L * value.length();
			}
		}
		return bytes;
	}

	private static long list(List<String> values) {
		return values == null ? 0 : OBJECT_BYTES + chars(values.toArray(new String[0]));
	}

	/**
	 * 从查库结果组装快照。
	 *
	 * @param skus      在售 SKU（查询本身不取库存与成本价）
	 * @param specsBySku skuId → 该 SKU 的规格值（已按 sort 排好）
	 * @param specTree  规格树
	 */
	public static GoodsDetailSnapshot of(GoodsSpu spu, List<GoodsSku> skus,
										 Map<String, List<GoodsSkuSpecValue>> specsBySku, List<GoodsSpecVO> specTree) {
		GoodsDetailSnapshot snapshot = new GoodsDetailSnapshot();
		snapshot.setId(spu.getId());
		snapshot.setTenantId(spu.getTenantId());
		snapshot.setShopId(spu.getShopId());
		snapshot.setSpuCode(spu.getSpuCode());
		snapshot.setName(spu.getName());
		snapshot.setSellPoint(spu.getSellPoint());
		snapshot.setDescription(spu.getDescription());
		snapshot.setCategoryFirst(spu.getCategoryFirst());
		snapshot.setCategorySecond(spu.getCategorySecond());
		snapshot.setCategoryThird(spu.getCategoryThird());
		snapshot.setVideoUrl(spu.getVideoUrl());
		snapshot.setPicUrls(toList(spu.getPicUrls()));
		snapshot.setShelf(spu.getShelf());
		snapshot.setSort(spu.getSort());
		snapshot.setPriceDown(spu.getPriceDown());
		snapshot.setPriceUp(spu.getPriceUp());
		snapshot.setCreateTime(spu.getCreateTime());
		snapshot.setUpdateTime(spu.getUpdateTime());
		snapshot.setSpecType(spu.getSpecType());
		snapshot.setDelFlag(spu.getDelFlag());
		snapshot.setFreightType(spu.getFreightType());
		snapshot.setFreightAmount(spu.getFreightAmount());
		snapshot.setVerifyStatus(spu.getVerifyStatus());
		snapshot.setBrandId(spu.getBrandId());
		snapshot.setLabel(toList(spu.getLabel()));
		snapshot.setParameterJson(spu.getParameterJson());
		snapshot.setViewNum(spu.getViewNum());
		List<Sku> skuSnapshots = new ArrayList<>(skus.size());
		for (GoodsSku sku : skus) {
			skuSnapshots.add(Sku.of(sku, specsBySku.getOrDefault(sku.getId(), List.of())));
		}
		snapshot.setSkus(skuSnapshots);
		snapshot.setSpecTree(specTree.stream().map(Spec::of).collect(Collectors.toCollection(ArrayList::new)));
		return snapshot;
	}

	/**
	 * 转回响应对象，库存、SKU 版本号、销量取自 {@code stock}（为 null 时销量为空、库存记 0）。
	 * 每次都新建对象，调用方可以随意改，不会污染缓存值。
	 */
	public GoodsSpu toGoodsSpu(GoodsStockSnapshot stock) {
		Map<String, GoodsStockSnapshot.SkuStock> stockBySku = stock == null || stock.getSkus() == null ? Map.of()
				: stock.getSkus().stream().collect(Collectors.toMap(GoodsStockSnapshot.SkuStock::getSkuId,
						Function.identity(), (a, b) -> a));
		GoodsSpu spu = new GoodsSpu();
		spu.setId(id);
		spu.setTenantId(tenantId);
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
		spu.setSaleNum(stock == null ? null : stock.getSaleNum());
		spu.setCreateTime(createTime);
		spu.setUpdateTime(updateTime);
		spu.setSpecType(specType);
		spu.setDelFlag(delFlag);
		spu.setFreightType(freightType);
		spu.setFreightAmount(freightAmount);
		spu.setVerifyStatus(verifyStatus);
		spu.setBrandId(brandId);
		spu.setLabel(toArray(label));
		spu.setParameterJson(parameterJson);
		spu.setViewNum(viewNum);
		List<GoodsSku> goodsSkus = new ArrayList<>();
		if (skus != null) {
			for (Sku sku : skus) {
				goodsSkus.add(sku.toGoodsSku(stockBySku.get(sku.getId())));
			}
		}
		spu.setSkus(goodsSkus);
		return spu;
	}

	/** 规格树转回响应对象（与查库时 MyBatis 映射出的 {@link GoodsSpecVO} 一致）。 */
	public List<GoodsSpecVO> toSpecTree() {
		List<GoodsSpecVO> tree = new ArrayList<>();
		if (specTree != null) {
			specTree.forEach(spec -> tree.add(spec.toVO()));
		}
		return tree;
	}

	private static List<String> toList(String[] values) {
		return values == null ? null : new ArrayList<>(Arrays.asList(values));
	}

	private static String[] toArray(List<String> values) {
		return values == null ? null : values.toArray(new String[0]);
	}
}
