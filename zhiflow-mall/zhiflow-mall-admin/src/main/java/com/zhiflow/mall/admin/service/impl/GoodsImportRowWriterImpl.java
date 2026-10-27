package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.service.GoodsImportContext;
import com.zhiflow.mall.admin.service.GoodsImportRowWriter;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.common.constant.GoodsImportConstants;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.dto.SpuSpecDTO;
import com.zhiflow.mall.common.entity.GoodsImportTask;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.enums.GoodsImportTemplateType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static com.zhiflow.mall.admin.service.impl.GoodsImportExcelServiceImpl.parseDecimal;
import static com.zhiflow.mall.admin.service.impl.GoodsImportExcelServiceImpl.parseInt;
import static com.zhiflow.mall.admin.service.impl.GoodsImportExcelServiceImpl.splitMulti;

/**
 * 把一行表格数据变成 goods_spu / goods_sku / goods_spu_extension。
 *
 * <p>新建走 {@link GoodsSpuService#save1}，不自己拼 insert —— 那条路上还挂着默认店铺、审核状态、运费默认值
 * 与搜索索引同步，绕过去会造出一批「搜不到」的残缺商品。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsImportRowWriterImpl implements GoodsImportRowWriter {

	private final GoodsSpuService goodsSpuService;
	private final GoodsSkuService goodsSkuService;
	private final GoodsSkuSpecValueService goodsSkuSpecValueService;
	private final GoodsDetailCacheEviction goodsDetailCacheEviction;

	@Override
	@Transactional(rollbackFor = Exception.class, propagation = Propagation.REQUIRES_NEW)
	public boolean write(GoodsImportTask task, List<GoodsImportRowDTO> group, GoodsImportContext context) {
		GoodsImportRowDTO head = group.get(0);
		String spuCode = trim(head.get(GoodsImportColumn.SPU_CODE.getField()));
		if (StringUtils.isBlank(spuCode)) {
			throw new BizException("缺少必填项：商品编码");
		}
		GoodsSpu existing = goodsSpuService.getOne(Wrappers.<GoodsSpu>lambdaQuery()
				.eq(GoodsSpu::getSpuCode, spuCode).last("limit 1"));

		boolean updateTemplate = GoodsImportTemplateType.UPDATE.getCode().equals(task.getTemplateType());
		if (updateTemplate) {
			if (existing == null) {
				throw new BizException("商品编码不存在，增量更新模板只能更新已有商品");
			}
			applyUpdate(task, group, context, existing);
			goodsDetailCacheEviction.evictAfterCommit(existing);
			return false;
		}
		if (existing != null) {
			if (!GoodsImportConstants.DUP_OVERWRITE.equals(task.getDupStrategy())) {
				throw new BizException("商品编码已存在");
			}
			applyUpdate(task, group, context, existing);
			goodsDetailCacheEviction.evictAfterCommit(existing);
			return false;
		}
		applyCreate(task, group, context, spuCode);
		return true;
	}

	// ===================== 新建 =====================

	private void applyCreate(GoodsImportTask task, List<GoodsImportRowDTO> group,
							 GoodsImportContext context, String spuCode) {
		GoodsImportRowDTO row = group.get(0);
		GoodsSpu spu = new GoodsSpu();
		spu.setShopId(task.getShopId());
		spu.setSpuCode(spuCode);
		spu.setName(trim(row.get(GoodsImportColumn.NAME.getField())));
		spu.setSellPoint(trim(row.get(GoodsImportColumn.SELL_POINT.getField())));
		spu.setDescription(row.get(GoodsImportColumn.DESCRIPTION.getField()));
		spu.setVideoUrl(trim(row.get(GoodsImportColumn.VIDEO_URL.getField())));
		spu.setPicUrls(toArray(row.get(GoodsImportColumn.PIC_URLS.getField())));
		spu.setBrandId(resolveBrand(row, context));
		spu.setSort(defaultInt(row, GoodsImportColumn.SORT, 0));
		spu.setSaleNum(defaultInt(row, GoodsImportColumn.SALE_NUM, 0));
		spu.setStockWarnLine(parseInt(row.get(GoodsImportColumn.STOCK_WARN_LINE.getField())));

		// 高级设置「导入后一律下架」优先于表格里的上架状态：批量货一般要先核价再放出去
		String shelf = option(row, GoodsImportColumn.SHELF);
		spu.setShelf(isYes(task.getForceOffShelf()) ? MallConstants.GOODS_STATUS_0
				: StringUtils.defaultIfBlank(shelf, MallConstants.GOODS_STATUS_0));

		spu.setCategoryFirst(task.getCategoryFirst());
		spu.setCategorySecond(task.getCategorySecond());
		spu.setCategoryThird(task.getCategoryThird());

		List<String> specNames = splitMulti(row.get(GoodsImportColumn.SPEC_NAMES.getField()));
		List<GoodsSku> skus = new ArrayList<>();
		for (int i = 0; i < group.size(); i++) {
			// SKU编码留空时的兜底：单规格沿用商品编码；多规格加序号，
			// 否则一个商品下几个 SKU 全叫同一个编码，盘点和对账分不出来（goods_sku 没有唯一约束拦这个）
			String fallbackCode = group.size() > 1 ? spuCode + "-" + (i + 1) : spuCode;
			skus.add(buildSku(task, group.get(i), fallbackCode));
		}
		spu.setSkus(skus);

		if (specNames.isEmpty()) {
			spu.setSpecType(MallConstants.SPU_SPEC_TYPE_0);
		} else {
			spu.setSpecType(MallConstants.SPU_SPEC_TYPE_1);
			// 规格项/规格值都必须是「商品规格」字典里已有的行。不在导入时自动建字典：
			// 运营一个错别字就会让「40mg*90片」和「40mg×90片」并存，之后前台按规格筛选直接分叉。
			List<String> specIds = new ArrayList<>();
			List<SpuSpecDTO> spuSpecs = new ArrayList<>();
			for (String specName : specNames) {
				String specId = context.getSpecIdByName().get(specName);
				if (specId == null) {
					throw new BizException("规格项「" + specName + "」不存在，请先到「商品规格」里维护");
				}
				specIds.add(specId);
				SpuSpecDTO dto = new SpuSpecDTO();
				dto.setId(specId);
				dto.setValue(specName);
				spuSpecs.add(dto);
			}
			spu.setSpuSpec(spuSpecs);
			for (int i = 0; i < group.size(); i++) {
				skus.get(i).setSpecs(buildSkuSpecValues(group.get(i), specNames, specIds, context));
			}
		}

		goodsSpuService.save1(spu);
	}

	/** 一行的规格值 → goods_sku_spec_value，与规格项按顺序一一对应（个数由校验保证相等） */
	private List<GoodsSkuSpecValue> buildSkuSpecValues(GoodsImportRowDTO row, List<String> specNames,
													   List<String> specIds, GoodsImportContext context) {
		List<String> values = splitMulti(row.get(GoodsImportColumn.SPEC_VALUES.getField()));
		if (values.size() != specIds.size()) {
			throw new BizException("「规格值」个数与「规格项」个数不一致");
		}
		List<GoodsSkuSpecValue> specs = new ArrayList<>();
		for (int i = 0; i < specIds.size(); i++) {
			String valueName = values.get(i);
			String valueId = context.getSpecValueIdByKey()
					.get(GoodsImportContext.specValueKey(specIds.get(i), valueName));
			if (valueId == null) {
				throw new BizException("规格项「" + specNames.get(i) + "」下不存在规格值「" + valueName
						+ "」，请先到「商品规格」里维护");
			}
			GoodsSkuSpecValue specValue = new GoodsSkuSpecValue();
			specValue.setSpecId(specIds.get(i));
			specValue.setSpecValueId(valueId);
			specValue.setSpecValueName(valueName);
			specs.add(specValue);
		}
		return specs;
	}

	private GoodsSku buildSku(GoodsImportTask task, GoodsImportRowDTO row, String fallbackCode) {
		GoodsSku sku = new GoodsSku();
		// SKU编码 2026-08-09 从模板下线，兜底规则成为唯一来源（见 GoodsImportColumn 头注）
		sku.setSkuCode(fallbackCode);
		sku.setSalesPrice(parseDecimal(row.get(GoodsImportColumn.SALES_PRICE.getField())));
		sku.setMarketPrice(parseDecimal(row.get(GoodsImportColumn.MARKET_PRICE.getField())));
		sku.setCostPrice(parseDecimal(row.get(GoodsImportColumn.COST_PRICE.getField())));
		sku.setStock(isYes(task.getZeroStock()) ? 0 : defaultInt(row, GoodsImportColumn.STOCK, 0));
		sku.setWeight(parseDecimal(row.get(GoodsImportColumn.WEIGHT.getField())));
		sku.setVolume(parseDecimal(row.get(GoodsImportColumn.VOLUME.getField())));
		sku.setEnable(MallConstants.GOODS_STATUS_1);
		return sku;
	}

	// ===================== 覆盖 / 增量更新 =====================

	/**
	 * 只覆盖表格里**填了值**的列，空单元格一律跳过。
	 *
	 * <p>不复用 {@code GoodsSpuService.updateById1}：那条路会按提交的 skus 全量重建规格结构
	 * （删光 goods_spu_spec / goods_sku_spec_value 再重插），而更新模板通常只填了价格库存几列，
	 * 调它等于拿一份残缺数据把商品的规格结构重写一遍。这里退化成字段级局部更新：
	 * SPU 列按非空覆盖，SKU 列按「规格值」定位到具体 SKU 后覆盖，**不新增也不删除规格**。
	 */
	private void applyUpdate(GoodsImportTask task, List<GoodsImportRowDTO> group,
							 GoodsImportContext context, GoodsSpu existing) {
		GoodsImportRowDTO row = group.get(0);
		GoodsSpu patch = new GoodsSpu();
		patch.setId(existing.getId());
		setIfPresent(row, GoodsImportColumn.NAME, patch::setName);
		setIfPresent(row, GoodsImportColumn.SELL_POINT, patch::setSellPoint);
		setIfPresent(row, GoodsImportColumn.DESCRIPTION, patch::setDescription);
		setIfPresent(row, GoodsImportColumn.VIDEO_URL, patch::setVideoUrl);
		if (StringUtils.isNotBlank(row.get(GoodsImportColumn.PIC_URLS.getField()))) {
			patch.setPicUrls(toArray(row.get(GoodsImportColumn.PIC_URLS.getField())));
		}
		if (StringUtils.isNotBlank(row.get(GoodsImportColumn.BRAND_NAME.getField()))) {
			patch.setBrandId(resolveBrand(row, context));
		}
		patch.setSort(parseInt(row.get(GoodsImportColumn.SORT.getField())));
		patch.setSaleNum(parseInt(row.get(GoodsImportColumn.SALE_NUM.getField())));
		patch.setStockWarnLine(parseInt(row.get(GoodsImportColumn.STOCK_WARN_LINE.getField())));
		String shelf = isYes(task.getForceOffShelf())
				? MallConstants.GOODS_STATUS_0 : optionOrNull(row, GoodsImportColumn.SHELF);
		patch.setShelf(shelf);

		// 先动 SKU：定位不到规格会在这里抛错整组判失败，早于 SPU 落库能少一次无谓的写
		updateSkus(task, group, existing);
		goodsSpuService.updateById(patch);
	}

	/**
	 * 把组内每一行落到对应的已有 SKU 上。
	 *
	 * <p>定位规则：填了「规格值」就按规格值组合精确匹配（多规格改价/改库存靠这条）；
	 * 没填就要求商品下**只有一个** SKU。既不新增也不删除 SKU——从一份只填了价格的表格里
	 * 推断「这个规格是不是该删」注定会误删，规格增删只能走商品编辑页。
	 */
	private void updateSkus(GoodsImportTask task, List<GoodsImportRowDTO> group, GoodsSpu existing) {
		boolean zeroStock = isYes(task.getZeroStock());
		List<GoodsImportRowDTO> effective = group.stream()
				.filter(row -> zeroStock || hasSkuValue(row))
				.toList();
		if (effective.isEmpty()) {
			return;
		}
		List<GoodsSku> skus = goodsSkuService.list(Wrappers.<GoodsSku>lambdaQuery()
				.eq(GoodsSku::getSpuId, existing.getId()));
		if (skus.isEmpty()) {
			throw new BizException("该商品下没有可更新的规格");
		}
		Map<String, GoodsSku> skuByCombo = skus.size() > 1 ? loadSkuCombos(existing.getId(), skus) : Map.of();

		for (GoodsImportRowDTO row : effective) {
			List<String> values = splitMulti(row.get(GoodsImportColumn.SPEC_VALUES.getField()));
			GoodsSku sku;
			if (values.isEmpty()) {
				if (skus.size() > 1) {
					throw new BizException("该商品是多规格商品，请在「规格值」列填明要更新哪个规格");
				}
				sku = skus.get(0);
			} else {
				sku = skuByCombo.get(comboKey(values));
				if (sku == null) {
					throw new BizException("该商品下找不到规格组合「" + String.join("|", values) + "」");
				}
			}
			updateOneSku(task, row, existing, sku, zeroStock);
		}
		if (isYes(task.getSyncPrice())) {
			refreshPriceRange(existing.getId());
		}
	}

	private boolean hasSkuValue(GoodsImportRowDTO row) {
		return hasAny(row, GoodsImportColumn.SALES_PRICE, GoodsImportColumn.MARKET_PRICE,
				GoodsImportColumn.COST_PRICE, GoodsImportColumn.STOCK, GoodsImportColumn.WEIGHT,
				GoodsImportColumn.VOLUME);
	}

	/**
	 * 已有 SKU 的「规格值组合 → SKU」索引。组合按规格值名称排序后拼接，
	 * 这样表格里规格值的书写顺序与商品建档时的顺序不一致也能对上。
	 */
	private Map<String, GoodsSku> loadSkuCombos(String spuId, List<GoodsSku> skus) {
		List<GoodsSkuSpecValue> values = goodsSkuSpecValueService.list(
				Wrappers.<GoodsSkuSpecValue>lambdaQuery().eq(GoodsSkuSpecValue::getSpuId, spuId));
		Map<String, List<String>> namesBySku = new HashMap<>();
		for (GoodsSkuSpecValue value : values) {
			namesBySku.computeIfAbsent(value.getSkuId(), k -> new ArrayList<>()).add(value.getSpecValueName());
		}
		Map<String, GoodsSku> map = new HashMap<>();
		for (GoodsSku sku : skus) {
			List<String> names = namesBySku.get(sku.getId());
			if (names != null && !names.isEmpty()) {
				map.put(comboKey(names), sku);
			}
		}
		return map;
	}

	private String comboKey(List<String> values) {
		return values.stream().map(String::trim).sorted().collect(Collectors.joining(""));
	}

	private void updateOneSku(GoodsImportTask task, GoodsImportRowDTO row, GoodsSpu existing,
							  GoodsSku sku, boolean zeroStock) {
		boolean syncPrice = isYes(task.getSyncPrice());
		if (syncPrice) {
			setDecimalIfPresent(row, GoodsImportColumn.SALES_PRICE, sku::setSalesPrice);
			setDecimalIfPresent(row, GoodsImportColumn.MARKET_PRICE, sku::setMarketPrice);
			setDecimalIfPresent(row, GoodsImportColumn.COST_PRICE, sku::setCostPrice);
		}
		setDecimalIfPresent(row, GoodsImportColumn.WEIGHT, sku::setWeight);
		setDecimalIfPresent(row, GoodsImportColumn.VOLUME, sku::setVolume);
		// SKU编码 / 一二级返佣已从模板下线，更新时一律不动这三列的既有值
		if (zeroStock) {
			sku.setStock(0);
		} else {
			Integer stock = parseInt(row.get(GoodsImportColumn.STOCK.getField()));
			if (stock != null) {
				sku.setStock(stock);
			}
		}
		// 与商品编辑同一口径：不参与 @Version 乐观锁 CAS，客户端/导入文件都不可靠地持有 version
		sku.setVersion(null);
		if (!goodsSkuService.updateById(sku)) {
			throw new BizException("规格更新失败，请稍后重试");
		}
	}

	/**
	 * 价格区间按**该商品全部 SKU** 重算，不能只看刚改过的那条：
	 * 多规格商品只调了其中一个规格的价，price_down/price_up 仍要覆盖所有规格，
	 * 否则前台商品卡上的「¥最低价起」会错。
	 */
	private void refreshPriceRange(String spuId) {
		List<GoodsSku> all = goodsSkuService.list(Wrappers.<GoodsSku>lambdaQuery()
				.eq(GoodsSku::getSpuId, spuId));
		BigDecimal down = null;
		BigDecimal up = null;
		for (GoodsSku item : all) {
			if (!MallConstants.GOODS_STATUS_1.equals(item.getEnable()) || item.getSalesPrice() == null) {
				continue;
			}
			BigDecimal sales = item.getSalesPrice();
			BigDecimal market = item.getMarketPrice() == null ? sales : item.getMarketPrice();
			down = down == null || down.compareTo(sales) > 0 ? sales : down;
			up = up == null || up.compareTo(market) < 0 ? market : up;
		}
		if (down == null) {
			return;
		}
		GoodsSpu price = new GoodsSpu();
		price.setId(spuId);
		price.setPriceDown(down);
		price.setPriceUp(up);
		goodsSpuService.updateById(price);
	}

	// ===================== 取值小工具 =====================

	private String resolveBrand(GoodsImportRowDTO row, GoodsImportContext context) {
		String name = trim(row.get(GoodsImportColumn.BRAND_NAME.getField()));
		if (StringUtils.isBlank(name)) {
			return null;
		}
		String id = context.getBrandIdByName().get(name);
		if (id == null) {
			throw new BizException("品牌「" + name + "」不存在，请先到商品品牌里维护");
		}
		return id;
	}

	private String[] toArray(String raw) {
		List<String> list = splitMulti(raw);
		return list.isEmpty() ? null : list.toArray(new String[0]);
	}

	/** OPTION 列：中文标签 → 落库值，空值返回空串（新建时由调用方兜默认值） */
	private String option(GoodsImportRowDTO row, GoodsImportColumn column) {
		String raw = trim(row.get(column.getField()));
		return StringUtils.isBlank(raw) ? "" : StringUtils.defaultString(column.getOptions().get(raw));
	}

	/** OPTION 列：空值返回 null，用于「留空=不更新」 */
	private String optionOrNull(GoodsImportRowDTO row, GoodsImportColumn column) {
		String raw = trim(row.get(column.getField()));
		return StringUtils.isBlank(raw) ? null : column.getOptions().get(raw);
	}

	private Integer defaultInt(GoodsImportRowDTO row, GoodsImportColumn column, int fallback) {
		Integer value = parseInt(row.get(column.getField()));
		return value == null ? fallback : value;
	}

	private void setIfPresent(GoodsImportRowDTO row, GoodsImportColumn column, java.util.function.Consumer<String> setter) {
		String raw = row.get(column.getField());
		if (StringUtils.isNotBlank(raw)) {
			setter.accept(raw.trim());
		}
	}

	private void setDecimalIfPresent(GoodsImportRowDTO row, GoodsImportColumn column,
									 java.util.function.Consumer<BigDecimal> setter) {
		BigDecimal value = parseDecimal(row.get(column.getField()));
		if (value != null) {
			setter.accept(value);
		}
	}

	private boolean hasAny(GoodsImportRowDTO row, GoodsImportColumn... columns) {
		for (GoodsImportColumn column : columns) {
			if (StringUtils.isNotBlank(row.get(column.getField()))) {
				return true;
			}
		}
		return false;
	}

	private static boolean isYes(String flag) {
		return "1".equals(flag);
	}

	private static String trim(String raw) {
		return raw == null ? null : raw.trim();
	}
}
