package com.zhiflow.mall.common.enums;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 商品批量导入的「列」真源（SSOT）。
 *
 * <p>模板生成（表头/说明/示例/下拉）、上传解析（按表头定位列）、失败数据导出三处**只读本枚举**，
 * 任何一列的增删改只改这里一处。字段口径直接对齐现有实体，不另立第二套模型：
 * <ul>
 *   <li>{@link Scope#SPU} → {@code goods_spu}（com.zhiflow.mall.common.entity.GoodsSpu）</li>
 *   <li>{@link Scope#SKU} → {@code goods_sku}（GoodsSku，一行一个 SKU）</li>
 * </ul>
 *
 * <p><b>不进模板的字段是有意排除的</b>，不是漏了：类目（在弹窗表单里选，整批统一）、店铺、
 * 审核状态、销量/浏览量/收藏数等统计列。SKU 编码不进模板：留空自动兜底
 * （单规格=商品编码，多规格=编码-序号），见 {@code GoodsImportRowWriterImpl#buildSku}。
 */
public enum GoodsImportColumn {

	// ===== SPU 基础 =====
	SPU_CODE("商品编码", "spuCode", Scope.SPU, ValueType.TEXT, true, 32,
			"必填，全店唯一，不超过 32 字符；增量更新模板按本列匹配已有商品", "RX-SMGLT-001"),
	NAME("商品名称", "name", Scope.SPU, ValueType.TEXT, true, 100,
			"不超过 100 字", "诺和泰 司美格鲁肽注射液 1.34mg/ml"),
	SELL_POINT("卖点", "sellPoint", Scope.SPU, ValueType.TEXT, false, 200,
			"商品名下方的一句话推荐语", "GLP-1 周制剂，每周注射一次"),
	PIC_URLS("商品主图", "picUrls", Scope.SPU, ValueType.TEXT, true, 2000,
			"完整 http(s) 图片地址；多张用 | 或英文逗号分隔，第一张为主图",
			"https://example.com/images/a.png|https://example.com/images/b.png"),
	VIDEO_URL("商品视频", "videoUrl", Scope.SPU, ValueType.TEXT, false, 500,
			"完整 http(s) 视频地址，留空表示没有视频", ""),
	DESCRIPTION("商品详情", "description", Scope.SPU, ValueType.LONG_TEXT, false, 60000,
			"详情页富文本，可直接粘 <img src=\"...\"> 标签", ""),
	BRAND_NAME("品牌", "brandName", Scope.SPU, ValueType.TEXT, false, 50,
			"填品牌名称，需与「商品品牌」里已存在的名称完全一致；填错整行判失败", "示例品牌"),
	SHELF("上架状态", "shelf", Scope.SPU, ValueType.OPTION, false, 0,
			"留空按弹窗「高级设置」里的默认值", "下架",
			options("下架", "0", "上架", "1")),
	SORT("排序", "sort", Scope.SPU, ValueType.INT, false, 0,
			"数字越小越靠前，留空按 0", "0"),
	SALE_NUM("虚拟销量", "saleNum", Scope.SPU, ValueType.INT, false, 0,
			"仅用于前台展示的基数，留空按 0", "0"),
	STOCK_WARN_LINE("库存预警线", "stockWarnLine", Scope.SPU, ValueType.INT, false, 0,
			"聚合库存低于该值即在商品列表预警；留空按系统默认 20", "20"),

	// ===== 规格（多规格：同一商品编码填多行，每行一个 SKU）=====
	SPEC_NAMES("规格项", "specNames", Scope.SPU, ValueType.TEXT, false, 100,
			"多规格商品必填，多个规格项用 | 分隔，同一商品编码的每行都要填且必须完全一致；"
					+ "留空表示单规格。规格项须已存在于「商品规格」字典", "规格|包装"),
	SPEC_VALUES("规格值", "specValues", Scope.SKU, ValueType.TEXT, false, 200,
			"与「规格项」按 | 一一对应，个数必须相同；同一商品编码下不能出现重复组合。"
					+ "规格值须已存在于该规格项下", "40mg×90片|盒装"),

	// ===== SKU（一行一个 SKU）=====
	SALES_PRICE("销售价", "salesPrice", Scope.SKU, ValueType.DECIMAL, true, 0,
			"单位元，最多两位小数", "1298.00"),
	MARKET_PRICE("市场价", "marketPrice", Scope.SKU, ValueType.DECIMAL, false, 0,
			"划线价，需不低于销售价", "1580.00"),
	COST_PRICE("成本价", "costPrice", Scope.SKU, ValueType.DECIMAL, false, 0,
			"财务口径成本，仅后台可见", ""),
	STOCK("库存", "stock", Scope.SKU, ValueType.INT, true, 0,
			"非负整数", "100"),
	WEIGHT("重量(kg)", "weight", Scope.SKU, ValueType.DECIMAL, false, 0,
			"按重量计费的运费模板需要", "0.2"),
	VOLUME("体积(m³)", "volume", Scope.SKU, ValueType.DECIMAL, false, 0,
			"按体积计费的运费模板需要", "");

	/** 列归属的实体 */
	public enum Scope {SPU, SKU}

	/** 单元格取值类型，决定解析方式与失败原因归类 */
	public enum ValueType {TEXT, LONG_TEXT, INT, DECIMAL, OPTION, MULTI_OPTION}

	private final String header;
	private final String field;
	private final Scope scope;
	private final ValueType type;
	/** 「全量新增」模板下是否必填 */
	private final boolean requiredOnAdd;
	/** 文本最大长度，0 表示不限 */
	private final int maxLength;
	private final String note;
	private final String sample;
	/** OPTION / MULTI_OPTION 的「中文选项 → 落库值」映射，其余类型为空 */
	private final Map<String, String> options;

	GoodsImportColumn(String header, String field, Scope scope, ValueType type, boolean requiredOnAdd,
					  int maxLength, String note, String sample) {
		this(header, field, scope, type, requiredOnAdd, maxLength, note, sample, Collections.emptyMap());
	}

	GoodsImportColumn(String header, String field, Scope scope, ValueType type, boolean requiredOnAdd,
					  int maxLength, String note, String sample, Map<String, String> options) {
		this.header = header;
		this.field = field;
		this.scope = scope;
		this.type = type;
		this.requiredOnAdd = requiredOnAdd;
		this.maxLength = maxLength;
		this.note = note;
		this.sample = sample;
		this.options = options;
	}

	public String getHeader() {
		return header;
	}

	public String getField() {
		return field;
	}

	public Scope getScope() {
		return scope;
	}

	public ValueType getType() {
		return type;
	}

	public int getMaxLength() {
		return maxLength;
	}

	public String getSample() {
		return sample;
	}

	public Map<String, String> getOptions() {
		return options;
	}

	/** 下拉可选的中文标签，模板里做数据有效性用 */
	public List<String> getOptionLabels() {
		return List.copyOf(options.keySet());
	}

	/**
	 * 必填与否随模板类型变：增量更新只认「商品编码」这一个匹配键，其余列留空一律解释为
	 * 「本次不更新该字段」——否则运营为了改一个库存得把整行 30 多列重填一遍。
	 */
	public boolean isRequired(String templateType) {
		if (GoodsImportTemplateType.UPDATE.getCode().equals(templateType)) {
			return this == SPU_CODE;
		}
		return requiredOnAdd;
	}

	/** 模板说明行文案：必填标记 + 口径说明，增量更新模板下额外提示「留空=不更新」 */
	public String getNote(String templateType) {
		boolean update = GoodsImportTemplateType.UPDATE.getCode().equals(templateType);
		StringBuilder sb = new StringBuilder();
		if (isRequired(templateType)) {
			sb.append("【必填】");
		} else if (update) {
			sb.append("【留空=不更新】");
		}
		sb.append(note);
		return sb.toString();
	}

	/**
	 * 两种模板目前列集合相同、语义不同（新增 = 整行落库，更新 = 空单元格跳过），
	 * 差异集中在 {@link #isRequired(String)} 与 {@link #getNote(String)}。
	 * 保留本方法是为了将来某列需要在某种模板下隐藏时只改这一处。
	 */
	public static List<GoodsImportColumn> columnsOf(String templateType) {
		return List.of(values());
	}

	/** 按表头文本反查列；表头两端空格由本方法归一，其余差异视为未知列 */
	public static GoodsImportColumn ofHeader(String header) {
		if (header == null) {
			return null;
		}
		String key = header.trim();
		for (GoodsImportColumn column : values()) {
			if (column.header.equals(key)) {
				return column;
			}
		}
		return null;
	}

	/**
	 * 落库值 → 中文标签，{@link #ofHeader} 的值域版本，导出现有商品时用。
	 *
	 * <p>只对 OPTION / MULTI_OPTION 有意义；其余类型或匹配不上的值原样返回——
	 * 导出不是校验环节，遇到脏数据（比如历史遗留的 shelf='2'）原样写出去比丢值好，
	 * 运营在表里能看见，改成合法值再导回即可。
	 */
	public String labelOf(String value) {
		if (value == null || value.isEmpty() || options.isEmpty()) {
			return value;
		}
		for (Map.Entry<String, String> entry : options.entrySet()) {
			if (entry.getValue().equals(value)) {
				return entry.getKey();
			}
		}
		return value;
	}

	/** 供前端渲染「模板含 N 个字段」与字段说明用 */
	public static List<Map<String, Object>> describe(String templateType) {
		List<Map<String, Object>> list = new ArrayList<>();
		for (GoodsImportColumn column : columnsOf(templateType)) {
			Map<String, Object> item = new LinkedHashMap<>();
			item.put("header", column.header);
			item.put("field", column.field);
			item.put("scope", column.scope.name());
			item.put("type", column.type.name());
			item.put("required", column.isRequired(templateType));
			item.put("note", column.getNote(templateType));
			item.put("options", column.getOptionLabels());
			list.add(item);
		}
		return list;
	}

	private static Map<String, String> yesNo() {
		return options("否", "0", "是", "1");
	}

	private static Map<String, String> options(String... labelThenValue) {
		Map<String, String> map = new LinkedHashMap<>();
		for (int i = 0; i + 1 < labelThenValue.length; i += 2) {
			map.put(labelThenValue[i], labelThenValue[i + 1]);
		}
		return Collections.unmodifiableMap(map);
	}
}
