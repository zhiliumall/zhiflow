package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.service.GoodsBrandService;
import com.zhiflow.mall.admin.service.GoodsExportService;
import com.zhiflow.mall.admin.service.GoodsImportExcelService;
import com.zhiflow.mall.admin.service.GoodsSkuService;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpecService;
import com.zhiflow.mall.admin.service.GoodsSpecValueService;
import com.zhiflow.mall.admin.service.GoodsSpuService;
import com.zhiflow.mall.common.constant.GoodsImportConstants;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.entity.GoodsBrand;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpec;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.enums.GoodsImportTemplateType;
import com.zhiflow.mall.common.vo.GoodsImportIssueVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 商品导出。写出去的每一行都是「导入侧看得懂的一行」：
 * 一个 SKU 一行，多规格商品用同一个商品编码占多行，SPU 级字段只写在组头行——
 * 与 {@code GoodsImportRowGroups} / {@code GoodsImportRowWriterImpl.applyUpdate} 的分组语义严格对齐，
 * 否则导出的文件传回去会被判成「同编码重复」或把整品字段覆盖成第二个规格的值。
 *
 * <p><b>为什么不导出成「一个商品一行」</b>：更新侧定位 SKU 靠「规格值」列，
 * 多规格商品压成一行就没法表达「哪个规格改成多少钱」。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsExportServiceImpl implements GoodsExportService {

	private final GoodsSpuService goodsSpuService;
	private final GoodsSkuService goodsSkuService;
	private final GoodsSkuSpecValueService goodsSkuSpecValueService;
	private final GoodsSpecService goodsSpecService;
	private final GoodsSpecValueService goodsSpecValueService;
	private final GoodsBrandService goodsBrandService;
	private final GoodsImportExcelService goodsImportExcelService;

	/** 只读参考列（表头带前缀，导入时被忽略） */
	private static final String REF_SPU_ID = GoodsImportExcelServiceImpl.EXPORT_REF_PREFIX + "商品ID";
	private static final String REF_DESC_LEN = GoodsImportExcelServiceImpl.EXPORT_REF_PREFIX + "详情字数";
	private static final String REF_NOTE = GoodsImportExcelServiceImpl.EXPORT_REF_PREFIX + "备注";

	@Override
	public byte[] export(GoodsSpu query, List<String> ids) {
		List<GoodsSpu> spus = loadSpus(query, ids);
		if (spus.isEmpty()) {
			throw new BizException("当前筛选条件下没有商品可导出");
		}
		List<String> spuIds = spus.stream().map(GoodsSpu::getId).filter(StringUtils::isNotBlank).toList();

		Map<String, List<GoodsSku>> skusBySpu = loadSkus(spuIds);
		Map<String, List<GoodsSkuSpecValue>> specValuesBySku = loadSpecValues(spuIds);
		Map<String, GoodsSpecValue> specValueDict = loadSpecValueDict(specValuesBySku.values());
		Map<String, String> specNameById = goodsSpecService.list(Wrappers.<GoodsSpec>lambdaQuery()
						.select(GoodsSpec::getId, GoodsSpec::getName)).stream()
				.filter(spec -> StringUtils.isNotBlank(spec.getName()))
				.collect(Collectors.toMap(GoodsSpec::getId, GoodsSpec::getName, (a, b) -> a));
		Map<String, String> brandNameById = goodsBrandService.list(Wrappers.<GoodsBrand>lambdaQuery()
						.select(GoodsBrand::getId, GoodsBrand::getBrandName)).stream()
				.filter(brand -> StringUtils.isNotBlank(brand.getBrandName()))
				.collect(Collectors.toMap(GoodsBrand::getId, GoodsBrand::getBrandName, (a, b) -> a));

		List<Map<String, String>> rows = new ArrayList<>();
		int skipped = 0;
		for (GoodsSpu spu : spus) {
			List<GoodsSku> skus = skusBySpu.getOrDefault(spu.getId(), List.of());
			int rowsOfSpu = Math.max(skus.size(), 1);
			// 按商品边界截断：不能把一个多规格商品的规格行切一半，剩下的行传回去会判「规格组合缺失」
			if (rows.size() + rowsOfSpu > GoodsImportConstants.MAX_ROWS) {
				skipped++;
				continue;
			}
			rows.addAll(buildRows(spu, skus, specValuesBySku, specValueDict, specNameById,
					brandNameById));
		}
		if (rows.isEmpty()) {
			throw new BizException("该商品的规格行数超过单次上限 " + GoodsImportConstants.MAX_ROWS + " 行，请缩小筛选范围");
		}
		markImportIssues(rows);
		if (skipped > 0) {
			// 静默截断会让运营以为「全导出来了」，改完传回去发现少了一批
			log.warn("商品导出达到 {} 行上限，跳过 {} 个商品", GoodsImportConstants.MAX_ROWS, skipped);
			mergeNote(rows.get(0), "本次已达 " + GoodsImportConstants.MAX_ROWS + " 行上限，还有 "
					+ skipped + " 个商品未导出，请用筛选条件分批导");
		}
		return goodsImportExcelService.buildExportWorkbook(rows);
	}

	/**
	 * 导出前先按导入侧的规则体检一遍，把「原样传回去也会失败」的行标在参考列上。
	 *
	 * <p>库里存在导入校验过不了的历史数据是常态（市场价低于销售价、图片存的是相对路径……）。不提前标出来的话，运营只是想改个库存，
	 * 传回去却收到一堆跟自己改动无关的失败行，只能逐条猜。
	 */
	private void markImportIssues(List<Map<String, String>> rows) {
		List<GoodsImportRowDTO> dtos = new ArrayList<>();
		for (int i = 0; i < rows.size(); i++) {
			GoodsImportRowDTO dto = new GoodsImportRowDTO();
			// 行号按导出文件的真实行算：第 1 行是表头
			dto.setRowNo(i + 2);
			dto.setValues(rows.get(i));
			dtos.add(dto);
		}
		List<GoodsImportIssueVO> issues;
		try {
			issues = goodsImportExcelService.validate(dtos, GoodsImportTemplateType.UPDATE.getCode());
		} catch (Exception e) {
			// 体检只是增值信息，挂了不该让整个导出失败
			log.warn("导出前预校验失败，跳过标注", e);
			return;
		}
		for (GoodsImportIssueVO issue : issues) {
			int index = (issue.getRowNo() == null ? 0 : issue.getRowNo()) - 2;
			if (index < 0 || index >= rows.size()) {
				continue;
			}
			String text = StringUtils.isBlank(issue.getHeader())
					? StringUtils.defaultString(issue.getReason())
					: issue.getHeader() + "：" + StringUtils.defaultString(issue.getReason());
			mergeNote(rows.get(index), "原数据不合规，导回时会被拦下 → " + text);
		}
	}

	private void mergeNote(Map<String, String> row, String note) {
		String old = row.get(REF_NOTE);
		row.put(REF_NOTE, StringUtils.isBlank(old) ? note : old + "；" + note);
	}

	/**
	 * 勾选导出优先；否则按列表筛选条件取前 {@link GoodsImportConstants#MAX_ROWS} 个商品——
	 * 单规格商品一个占一行，所以商品数上限取行数上限即可。
	 */
	private List<GoodsSpu> loadSpus(GoodsSpu query, List<String> ids) {
		if (ids != null && !ids.isEmpty()) {
			List<String> limited = ids.size() > GoodsImportConstants.MAX_ROWS
					? ids.subList(0, GoodsImportConstants.MAX_ROWS) : ids;
			return goodsSpuService.list(Wrappers.<GoodsSpu>lambdaQuery().in(GoodsSpu::getId, limited));
		}
		Page<GoodsSpu> page = new Page<>(1, GoodsImportConstants.MAX_ROWS);
		// 复用列表页的 page1：筛选口径与运营在页面上看到的结果一模一样，不另写一套 wrapper
		return goodsSpuService.page1(page, query == null ? new GoodsSpu() : query).getRecords();
	}

	private Map<String, List<GoodsSku>> loadSkus(List<String> spuIds) {
		if (spuIds.isEmpty()) {
			return Map.of();
		}
		List<GoodsSku> skus = goodsSkuService.list(Wrappers.<GoodsSku>lambdaQuery()
				.in(GoodsSku::getSpuId, spuIds));
		Map<String, List<GoodsSku>> map = new HashMap<>();
		for (GoodsSku sku : skus) {
			map.computeIfAbsent(sku.getSpuId(), k -> new ArrayList<>()).add(sku);
		}
		return map;
	}

	private Map<String, List<GoodsSkuSpecValue>> loadSpecValues(List<String> spuIds) {
		if (spuIds.isEmpty()) {
			return Map.of();
		}
		List<GoodsSkuSpecValue> values = goodsSkuSpecValueService.list(
				Wrappers.<GoodsSkuSpecValue>lambdaQuery().in(GoodsSkuSpecValue::getSpuId, spuIds));
		Map<String, List<GoodsSkuSpecValue>> map = new HashMap<>();
		for (GoodsSkuSpecValue value : values) {
			map.computeIfAbsent(value.getSkuId(), k -> new ArrayList<>()).add(value);
		}
		// 同一 SKU 的多个规格项按 sort 排，组内各行的「规格项」顺序才稳定一致
		map.values().forEach(list -> list.sort(Comparator.comparing(
				v -> v.getSort() == null ? Integer.MAX_VALUE : v.getSort())));
		return map;
	}

	/**
	 * goods_sku_spec_value 只存 {@code spec_value_id}——实体上的 {@code specId} / {@code specValueName}
	 * 是 {@code @TableField(exist = false)} 的非持久化字段，直接读一律为 null。
	 * 规格项与规格值名称必须再经 goods_spec_value 关一次表才拿得到。
	 */
	private Map<String, GoodsSpecValue> loadSpecValueDict(Collection<List<GoodsSkuSpecValue>> grouped) {
		List<String> ids = grouped.stream().flatMap(List::stream)
				.map(GoodsSkuSpecValue::getSpecValueId)
				.filter(StringUtils::isNotBlank)
				.distinct().toList();
		if (ids.isEmpty()) {
			return Map.of();
		}
		return goodsSpecValueService.list(Wrappers.<GoodsSpecValue>lambdaQuery()
						.in(GoodsSpecValue::getId, ids)).stream()
				.collect(Collectors.toMap(GoodsSpecValue::getId, v -> v, (a, b) -> a));
	}

	/**
	 * 一个商品 → 若干行。组头行带全部 SPU 级列，后续行只带商品编码 + 规格 + 该规格自己的 SKU 列，
	 * 与新增模板示例里的多规格写法一致。
	 */
	private List<Map<String, String>> buildRows(GoodsSpu spu, List<GoodsSku> skus,
												Map<String, List<GoodsSkuSpecValue>> specValuesBySku,
												Map<String, GoodsSpecValue> specValueDict,
												Map<String, String> specNameById,
												Map<String, String> brandNameById) {
		// 规格项顺序以第一个 SKU 为准，组内各行必须完全一致（含顺序），否则导入侧判整组失败
		List<String> specOrder = new ArrayList<>();
		if (!skus.isEmpty()) {
			for (GoodsSkuSpecValue value : specValuesBySku.getOrDefault(skus.get(0).getId(), List.of())) {
				GoodsSpecValue dict = specValueDict.get(value.getSpecValueId());
				if (dict != null && StringUtils.isNotBlank(dict.getSpecId())
						&& !specOrder.contains(dict.getSpecId())) {
					specOrder.add(dict.getSpecId());
				}
			}
		}
		String specNames = specOrder.stream()
				.map(id -> StringUtils.defaultString(specNameById.get(id), ""))
				.filter(StringUtils::isNotBlank)
				.collect(Collectors.joining(GoodsImportConstants.MULTI_VALUE_SEPARATOR));

		List<Map<String, String>> rows = new ArrayList<>();
		if (skus.isEmpty()) {
			// 没有 SKU 的商品（历史脏数据）也导出来，至少 SPU 级字段可改；价格库存列留空
			rows.add(headRow(spu, brandNameById, specNames, null, null));
			return rows;
		}
		for (int i = 0; i < skus.size(); i++) {
			GoodsSku sku = skus.get(i);
			String specValues = specValues(sku, specOrder, specValuesBySku, specValueDict);
			Map<String, String> row = i == 0
					? headRow(spu, brandNameById, specNames, sku, specValues)
					: memberRow(spu, specNames, specValues);
			if (i > 0) {
				putSku(row, sku);
			}
			if (skus.size() > 1 && StringUtils.isBlank(specValues)) {
				// 多 SKU 却查不到规格值：这样的行传回去定位不到规格，标出来让运营去商品编辑页处理
				mergeNote(row, "该规格缺少规格值数据，导回时无法定位，请到商品编辑页修复");
			}
			rows.add(row);
		}
		return rows;
	}

	private String specValues(GoodsSku sku, List<String> specOrder,
							  Map<String, List<GoodsSkuSpecValue>> specValuesBySku,
							  Map<String, GoodsSpecValue> specValueDict) {
		List<GoodsSkuSpecValue> values = specValuesBySku.getOrDefault(sku.getId(), List.of());
		if (values.isEmpty()) {
			return "";
		}
		Map<String, String> nameBySpecId = new LinkedHashMap<>();
		for (GoodsSkuSpecValue value : values) {
			GoodsSpecValue dict = specValueDict.get(value.getSpecValueId());
			if (dict != null) {
				nameBySpecId.putIfAbsent(dict.getSpecId(), dict.getName());
			}
		}
		List<String> ordered = new ArrayList<>();
		for (String specId : specOrder) {
			String name = nameBySpecId.get(specId);
			if (StringUtils.isBlank(name)) {
				// 缺了某一项就退回本 SKU 自己的顺序，宁可导出得不整齐也不要凭空补一个空值占位
				return nameBySpecId.values().stream()
						.filter(StringUtils::isNotBlank)
						.collect(Collectors.joining(GoodsImportConstants.MULTI_VALUE_SEPARATOR));
			}
			ordered.add(name);
		}
		return String.join(GoodsImportConstants.MULTI_VALUE_SEPARATOR, ordered);
	}

	private Map<String, String> headRow(GoodsSpu spu, Map<String, String> brandNameById,
										String specNames, GoodsSku sku, String specValues) {
		Map<String, String> row = new LinkedHashMap<>();
		put(row, GoodsImportColumn.SPU_CODE, spu.getSpuCode());
		put(row, GoodsImportColumn.NAME, spu.getName());
		put(row, GoodsImportColumn.SELL_POINT, spu.getSellPoint());
		put(row, GoodsImportColumn.PIC_URLS, join(spu.getPicUrls()));
		put(row, GoodsImportColumn.VIDEO_URL, spu.getVideoUrl());
		put(row, GoodsImportColumn.BRAND_NAME, brandNameById.get(spu.getBrandId()));
		put(row, GoodsImportColumn.SHELF, GoodsImportColumn.SHELF.labelOf(spu.getShelf()));
		put(row, GoodsImportColumn.SORT, text(spu.getSort()));
		put(row, GoodsImportColumn.SALE_NUM, text(spu.getSaleNum()));
		put(row, GoodsImportColumn.STOCK_WARN_LINE, text(spu.getStockWarnLine()));
		put(row, GoodsImportColumn.SPEC_NAMES, specNames);
		put(row, GoodsImportColumn.SPEC_VALUES, specValues);
		if (sku != null) {
			putSku(row, sku);
		}

		// 商品详情是唯一的富文本列：超过 Excel 单元格上限的导成空白（空白=不修改），
		// 并在参考列写明真实长度——截断版被原样导回会把详情悄悄改短
		String description = StringUtils.defaultString(spu.getDescription());
		putLongText(row, GoodsImportColumn.DESCRIPTION, description, REF_DESC_LEN);
		row.put(REF_SPU_ID, spu.getId());
		return row;
	}

	/** 多规格商品的非组头行：只认商品编码 + 规格值，整品字段一律留空（留空=不修改） */
	private Map<String, String> memberRow(GoodsSpu spu, String specNames, String specValues) {
		Map<String, String> row = new LinkedHashMap<>();
		put(row, GoodsImportColumn.SPU_CODE, spu.getSpuCode());
		put(row, GoodsImportColumn.SPEC_NAMES, specNames);
		put(row, GoodsImportColumn.SPEC_VALUES, specValues);
		return row;
	}

	private void putSku(Map<String, String> row, GoodsSku sku) {
		put(row, GoodsImportColumn.SALES_PRICE, text(sku.getSalesPrice()));
		put(row, GoodsImportColumn.MARKET_PRICE, text(sku.getMarketPrice()));
		put(row, GoodsImportColumn.COST_PRICE, text(sku.getCostPrice()));
		put(row, GoodsImportColumn.STOCK, text(sku.getStock()));
		put(row, GoodsImportColumn.WEIGHT, text(sku.getWeight()));
		put(row, GoodsImportColumn.VOLUME, text(sku.getVolume()));
	}

	private void putLongText(Map<String, String> row, GoodsImportColumn column, String value, String refKey) {
		int limit = goodsImportExcelService.cellTextLimit();
		if (value.length() > limit) {
			row.put(refKey, value.length() + " 字，超出单元格上限已留空");
			return;
		}
		row.put(refKey, String.valueOf(value.length()));
		put(row, column, value);
	}

	private void put(Map<String, String> row, GoodsImportColumn column, String value) {
		if (StringUtils.isNotBlank(value)) {
			row.put(column.getField(), value);
		}
	}

	private String join(String[] values) {
		if (values == null || values.length == 0) {
			return "";
		}
		return String.join(GoodsImportConstants.MULTI_VALUE_SEPARATOR, values);
	}

	private String text(Integer value) {
		return value == null ? "" : String.valueOf(value);
	}

	/** 金额去掉多余的尾零：库里 19.8000 导出成 19.8，回传时按数值解析，不影响精度 */
	private String text(BigDecimal value) {
		return value == null ? "" : value.stripTrailingZeros().toPlainString();
	}
}
