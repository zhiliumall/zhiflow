package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.service.GoodsImportExcelService;
import com.zhiflow.mall.common.constant.GoodsImportConstants;
import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.enums.GoodsImportColumn;
import com.zhiflow.mall.common.enums.GoodsImportTemplateType;
import com.zhiflow.mall.common.utils.GoodsImportRowGroups;
import com.zhiflow.mall.common.vo.GoodsImportIssueVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CellType;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.DataValidation;
import org.apache.poi.ss.usermodel.DataValidationConstraint;
import org.apache.poi.ss.usermodel.DataValidationHelper;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.util.CellRangeAddressList;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * POI 实现。
 *
 * <p><b>模板为什么不把「说明」和「示例」放进数据行</b>：老模板
 * （public/template-batch-goods.xlsx）把示例商品直接写在数据区，解析时靠"运营记得删掉"，
 * 删漏就会真的导进三条华为测试商品。这里把说明与示例全部挪到第二个 sheet，
 * 数据 sheet 严格是「第 1 行表头 + 第 2 行起数据」，解析没有歧义。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class GoodsImportExcelServiceImpl implements GoodsImportExcelService {

	/**
	 * 必填判定统一入口：模板生成、说明页、表头星号、缺列校验、缺值校验必须全部走这里——
	 * 只改校验不改模板会得到「模板标着必填、留空却能过」的错位。
	 */
	private boolean required(GoodsImportColumn column, String templateType) {
		return column.isRequired(templateType);
	}

	/** 数据 sheet 名 */
	private static final String SHEET_DATA = "商品数据";
	/** 示例 sheet 名。解析只认「商品数据」页，所以这页永远不会被误导入 */
	private static final String SHEET_SAMPLE = "填写示例（本页不导入）";
	/** 说明 sheet 名 */
	private static final String SHEET_HELP = "填写说明";
	/** 下拉的作用行数上限：模板本身给 1000 行余量，超出部分不做数据有效性，不影响导入 */
	private static final int VALIDATION_ROWS = 1000;
	/** 失败数据文件的前两列 */
	private static final String FAIL_COL_ROW_NO = "原表行号";
	private static final String FAIL_COL_REASON = "失败原因";
	/** 导出文件的说明页 */
	private static final String SHEET_EXPORT_HELP = "导出说明（必读）";
	/** 导出文件里只读参考列的表头前缀，解析时认不出这些表头会自动忽略 */
	public static final String EXPORT_REF_PREFIX = "参考·";
	/**
	 * POI 写单元格的字符上限（Excel 规格 32767）。超过会直接抛
	 * IllegalArgumentException，所以富文本列导出前必须自己判长度——
	 * 不能截断后写入：截断版一旦被原样导回去，商品详情就被悄悄改短了。
	 */
	private static final int CELL_TEXT_LIMIT = 32767;

	private static final DataFormatter FORMATTER = new DataFormatter();

	@Override
	public byte[] buildTemplate(String templateType) {
		GoodsImportTemplateType type = GoodsImportTemplateType.of(templateType);
		List<GoodsImportColumn> columns = GoodsImportColumn.columnsOf(type.getCode());
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet data = workbook.createSheet(SHEET_DATA);
			CellStyle requiredStyle = headerStyle(workbook, true);
			CellStyle optionalStyle = headerStyle(workbook, false);

			Row header = data.createRow(0);
			header.setHeightInPoints(24);
			for (int i = 0; i < columns.size(); i++) {
				GoodsImportColumn column = columns.get(i);
				boolean required = required(column, type.getCode());
				Cell cell = header.createCell(i);
				cell.setCellValue(required ? column.getHeader() + "*" : column.getHeader());
				cell.setCellStyle(required ? requiredStyle : optionalStyle);
				data.setColumnWidth(i, columnWidth(column));
				applyValidation(workbook, data, column, i);
			}
			// 冻结表头，运营往下拖到第 400 行还能看见自己在填哪一列
			data.createFreezePane(0, 1);

			buildSampleSheet(workbook, type, columns);
			buildHelpSheet(workbook, type, columns);

			workbook.write(out);
			return out.toByteArray();
		} catch (Exception e) {
			log.error("生成商品导入模板失败, type={}", templateType, e);
			throw new BizException("模板生成失败，请稍后重试");
		}
	}

	@Override
	public String templateFileName(String templateType) {
		return GoodsImportTemplateType.of(templateType).getFileLabel() + ".xlsx";
	}

	@Override
	public List<GoodsImportRowDTO> parse(InputStream in, String templateType) {
		GoodsImportTemplateType type = GoodsImportTemplateType.of(templateType);
		try (Workbook workbook = new XSSFWorkbook(in)) {
			Sheet sheet = workbook.getSheet(SHEET_DATA);
			if (sheet == null) {
				// 运营可能另存为、改过 sheet 名，退回第一个 sheet 而不是直接报错
				sheet = workbook.getNumberOfSheets() > 0 ? workbook.getSheetAt(0) : null;
			}
			if (sheet == null) {
				throw new BizException("文件里没有可读取的工作表，请使用下载的模板填写");
			}
			Row headerRow = sheet.getRow(sheet.getFirstRowNum());
			if (headerRow == null) {
				throw new BizException("表头行为空，请使用下载的模板填写");
			}

			Map<Integer, GoodsImportColumn> columnIndex = new LinkedHashMap<>();
			for (int i = headerRow.getFirstCellNum(); i < headerRow.getLastCellNum(); i++) {
				// 表头允许带必填星号，去掉再匹配；认不出的列直接忽略（比如失败数据文件带的「失败原因」）
				String text = StringUtils.removeEnd(cellText(headerRow.getCell(i)).trim(), "*");
				GoodsImportColumn column = GoodsImportColumn.ofHeader(text);
				if (column != null) {
					columnIndex.put(i, column);
				}
			}
			assertRequiredHeaders(type, columnIndex.values());

			List<GoodsImportRowDTO> rows = new ArrayList<>();
			for (int r = headerRow.getRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
				Row row = sheet.getRow(r);
				if (row == null) {
					continue;
				}
				Map<String, String> values = new LinkedHashMap<>();
				boolean blank = true;
				for (Map.Entry<Integer, GoodsImportColumn> entry : columnIndex.entrySet()) {
					String text = cellText(row.getCell(entry.getKey())).trim();
					values.put(entry.getValue().getField(), text);
					if (StringUtils.isNotBlank(text)) {
						blank = false;
					}
				}
				if (blank) {
					continue;
				}
				GoodsImportRowDTO dto = new GoodsImportRowDTO();
				dto.setRowNo(r + 1);
				dto.setValues(values);
				rows.add(dto);
				if (rows.size() > GoodsImportConstants.MAX_ROWS) {
					throw new BizException("单次最多导入 " + GoodsImportConstants.MAX_ROWS + " 条，请拆分文件后重试");
				}
			}
			if (rows.isEmpty()) {
				throw new BizException("文件里没有数据行，请确认已填写内容");
			}
			return rows;
		} catch (BizException e) {
			throw e;
		} catch (Exception e) {
			log.error("解析商品导入文件失败", e);
			throw new BizException("文件解析失败，请确认是用模板另存的 .xlsx 文件");
		}
	}

	@Override
	public List<GoodsImportIssueVO> validate(List<GoodsImportRowDTO> rows, String templateType) {
		String type = GoodsImportTemplateType.of(templateType).getCode();
		List<GoodsImportIssueVO> issues = new ArrayList<>();
		for (List<GoodsImportRowDTO> group : GoodsImportRowGroups.groupList(rows)) {
			GoodsImportRowDTO head = group.get(0);
			String name = head.get(GoodsImportColumn.NAME.getField());
			for (GoodsImportRowDTO row : group) {
				boolean isHead = row == head;
				for (GoodsImportColumn column : GoodsImportColumn.columnsOf(type)) {
					validateCell(row, column, type, name, issues, isHead);
				}
				validateCrossField(row, type, name, issues, isHead);
			}
			validateSpecGroup(group, name, issues);
		}
		return issues;
	}

	/**
	 * 组级规格校验。三条规则，任何一条不满足都判整组失败——多规格商品不存在「导一半」这种中间态。
	 */
	private void validateSpecGroup(List<GoodsImportRowDTO> group, String name, List<GoodsImportIssueVO> issues) {
		GoodsImportRowDTO head = group.get(0);
		List<String> headSpecs = splitMulti(head.get(GoodsImportColumn.SPEC_NAMES.getField()));

		if (group.size() > 1 && headSpecs.isEmpty()) {
			for (GoodsImportRowDTO row : group) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, GoodsImportColumn.SPEC_NAMES.getHeader(),
						GoodsImportConstants.ISSUE_REQUIRED,
						"商品编码在本文件里出现多次；若是多规格商品请填写「规格项 / 规格值」，否则请改成不同编码"));
			}
			return;
		}
		if (headSpecs.isEmpty()) {
			// 单规格：规格值填了也没处放，明确报错而不是静默丢弃
			if (StringUtils.isNotBlank(head.get(GoodsImportColumn.SPEC_VALUES.getField()))) {
				issues.add(GoodsImportIssueVO.of(head.getRowNo(), name, GoodsImportColumn.SPEC_NAMES.getHeader(),
						GoodsImportConstants.ISSUE_REQUIRED, "填了规格值就必须填写对应的「规格项」"));
			}
			return;
		}

		Set<String> combos = new HashSet<>();
		for (GoodsImportRowDTO row : group) {
			List<String> specs = splitMulti(row.get(GoodsImportColumn.SPEC_NAMES.getField()));
			if (!specs.equals(headSpecs)) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, GoodsImportColumn.SPEC_NAMES.getHeader(),
						GoodsImportConstants.ISSUE_FORMAT,
						"同一商品编码各行的「规格项」必须完全一致（含顺序），本行是「"
								+ String.join("|", specs) + "」，首行是「" + String.join("|", headSpecs) + "」"));
				continue;
			}
			List<String> values = splitMulti(row.get(GoodsImportColumn.SPEC_VALUES.getField()));
			if (values.size() != headSpecs.size()) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, GoodsImportColumn.SPEC_VALUES.getHeader(),
						GoodsImportConstants.ISSUE_FORMAT,
						"「规格值」个数（" + values.size() + "）与「规格项」个数（" + headSpecs.size() + "）不一致"));
				continue;
			}
			if (!combos.add(String.join(" ", values))) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, GoodsImportColumn.SPEC_VALUES.getHeader(),
						GoodsImportConstants.ISSUE_DUPLICATE,
						"同一商品下规格组合「" + String.join("|", values) + "」重复"));
			}
		}
	}

	@Override
	public byte[] buildFailWorkbook(String templateType, List<GoodsImportIssueVO> issues,
									Map<Integer, Map<String, String>> rowValues) {
		String type = GoodsImportTemplateType.of(templateType).getCode();
		List<GoodsImportColumn> columns = GoodsImportColumn.columnsOf(type);
		// 一行可能有多个字段问题，导出按行合并，原因用「；」拼接
		Map<Integer, String> reasonByRow = new LinkedHashMap<>();
		for (GoodsImportIssueVO issue : issues) {
			reasonByRow.merge(issue.getRowNo(), describeIssue(issue), (a, b) -> a + "；" + b);
		}

		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet(SHEET_DATA);
			CellStyle headStyle = headerStyle(workbook, false);
			CellStyle reasonStyle = headerStyle(workbook, true);

			Row header = sheet.createRow(0);
			header.createCell(0).setCellValue(FAIL_COL_ROW_NO);
			header.getCell(0).setCellStyle(headStyle);
			sheet.setColumnWidth(0, 2600);
			header.createCell(1).setCellValue(FAIL_COL_REASON);
			header.getCell(1).setCellStyle(reasonStyle);
			sheet.setColumnWidth(1, 12000);
			for (int i = 0; i < columns.size(); i++) {
				GoodsImportColumn column = columns.get(i);
				Cell cell = header.createCell(i + 2);
				cell.setCellValue(required(column, type) ? column.getHeader() + "*" : column.getHeader());
				cell.setCellStyle(headStyle);
				sheet.setColumnWidth(i + 2, columnWidth(column));
			}

			int r = 1;
			for (Map.Entry<Integer, String> entry : reasonByRow.entrySet()) {
				Row row = sheet.createRow(r++);
				row.createCell(0).setCellValue(entry.getKey() == null ? "" : String.valueOf(entry.getKey()));
				row.createCell(1).setCellValue(entry.getValue());
				Map<String, String> values = rowValues == null
						? null : rowValues.get(entry.getKey());
				if (values != null) {
					for (int i = 0; i < columns.size(); i++) {
						row.createCell(i + 2).setCellValue(
								StringUtils.defaultString(values.get(columns.get(i).getField())));
					}
				}
			}
			sheet.createFreezePane(2, 1);
			workbook.write(out);
			return out.toByteArray();
		} catch (Exception e) {
			log.error("生成失败数据文件失败", e);
			throw new BizException("失败数据导出失败，请稍后重试");
		}
	}

	@Override
	public int cellTextLimit() {
		return CELL_TEXT_LIMIT;
	}

	/**
	 * 现有商品导出。表头逐列照抄**增量更新模板**（含必填星号），行序由调用方保证
	 * 「同一商品编码的多个规格相邻」，回传时 {@code GoodsImportRowGroups} 才能把它们认成一个商品。
	 *
	 * <p>末尾的 {@code 参考·} 列是只读信息（商品ID、详情字数等）：解析按表头认列，
	 * 这些表头在 {@link GoodsImportColumn#ofHeader} 里查不到，回传时自动忽略，不必要求运营删掉。
	 */
	@Override
	public byte[] buildExportWorkbook(List<Map<String, String>> rows) {
		String type = GoodsImportTemplateType.UPDATE.getCode();
		List<GoodsImportColumn> columns = GoodsImportColumn.columnsOf(type);
		// 参考列按各行出现顺序收集，调用方增删参考项不用再改本方法
		Set<String> refKeys = new java.util.LinkedHashSet<>();
		for (Map<String, String> row : rows) {
			for (String key : row.keySet()) {
				if (key.startsWith(EXPORT_REF_PREFIX)) {
					refKeys.add(key);
				}
			}
		}

		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet(SHEET_DATA);
			CellStyle requiredStyle = headerStyle(workbook, true);
			CellStyle optionalStyle = headerStyle(workbook, false);

			Row header = sheet.createRow(0);
			header.setHeightInPoints(24);
			for (int i = 0; i < columns.size(); i++) {
				GoodsImportColumn column = columns.get(i);
				boolean required = required(column, type);
				Cell cell = header.createCell(i);
				cell.setCellValue(required ? column.getHeader() + "*" : column.getHeader());
				cell.setCellStyle(required ? requiredStyle : optionalStyle);
				sheet.setColumnWidth(i, columnWidth(column));
				applyValidation(workbook, sheet, column, i);
			}
			int refStart = columns.size();
			int r = 0;
			for (String key : refKeys) {
				Cell cell = header.createCell(refStart + r);
				cell.setCellValue(key);
				cell.setCellStyle(optionalStyle);
				sheet.setColumnWidth(refStart + r, 3600);
				r++;
			}

			for (int i = 0; i < rows.size(); i++) {
				Row row = sheet.createRow(i + 1);
				Map<String, String> values = rows.get(i);
				for (int c = 0; c < columns.size(); c++) {
					String value = values.get(columns.get(c).getField());
					if (StringUtils.isNotBlank(value)) {
						row.createCell(c).setCellValue(value);
					}
				}
				int rc = 0;
				for (String key : refKeys) {
					String value = values.get(key);
					if (StringUtils.isNotBlank(value)) {
						row.createCell(refStart + rc).setCellValue(value);
					}
					rc++;
				}
			}
			sheet.createFreezePane(1, 1);

			buildExportHelpSheet(workbook);
			workbook.write(out);
			return out.toByteArray();
		} catch (Exception e) {
			log.error("生成商品导出文件失败", e);
			throw new BizException("商品导出失败，请稍后重试");
		}
	}

	/**
	 * 导出文件的说明页。写的都是「不看会踩」的三条：留空语义、改价要勾同步价格、多规格靠规格值定位。
	 */
	private void buildExportHelpSheet(Workbook workbook) {
		Sheet sheet = workbook.createSheet(SHEET_EXPORT_HELP);
		CellStyle wrap = workbook.createCellStyle();
		wrap.setWrapText(true);
		wrap.setVerticalAlignment(VerticalAlignment.TOP);
		sheet.setColumnWidth(0, 24000);
		String[] lines = {
				"本文件是「现有商品导出」，表头与「增量更新模板」完全一致，改完直接在"
						+ "商品列表 →「批量导入」里选**增量更新模板**上传即可。",
				"1）留空 = 不修改。不想动的列请保持原样或清空，不要填占位符。",
				"2）「商品编码」是匹配已有商品的唯一依据，不要改；改了会被当成不存在的商品，整行失败。",
				"3）多规格商品占多行、商品编码相同：整品字段（名称/主图/详情等）只看第一行，"
						+ "其余行只用「规格值」定位到具体规格改价格库存。「规格值」列不要改。",
				"4）**要改销售价 / 市场价 / 成本价，上传时必须在「高级设置」里勾上「同步价格」**，"
						+ "否则价格列会被忽略（这是导入侧的既有开关，防止误改价）。",
				"5）「商品详情」是富文本，可直接粘 <img src=\"https://...\"> 标签换图；"
						+ "Excel 单元格最多 " + CELL_TEXT_LIMIT + " 字，原内容超过这个长度的已导成空白"
						+ "（见「参考·详情字数」列），留空不会覆盖原详情，要换整段直接写新内容即可。",
				"6）「参考·」开头的列是只读信息，导入时会被忽略，删不删都行。",
				"7）品牌、运费模板、规格项/规格值填的都是名称，必须与后台已维护的完全一致，否则整行失败。",
		};
		for (int i = 0; i < lines.length; i++) {
			Row row = sheet.createRow(i);
			row.setHeightInPoints(i == 0 ? 34 : 30);
			Cell cell = row.createCell(0);
			cell.setCellValue(lines[i]);
			cell.setCellStyle(wrap);
		}
	}

	// ===================== 模板细节 =====================

	/**
	 * 填写示例页：与数据页同样的表头 + 三行样例（一行单规格 + 两行同编码的多规格）。
	 *
	 * <p><b>示例为什么单独放一页而不是写在数据页里</b>：老模板
	 * （public/template-batch-goods.xlsx）把三条「华为测试商品」直接放在数据区，
	 * 靠运营记得删。解析只认「商品数据」页，示例放这里就永远不会被误导入，
	 * 运营要用直接整行复制到数据页即可。
	 */
	private void buildSampleSheet(Workbook workbook, GoodsImportTemplateType type, List<GoodsImportColumn> columns) {
		Sheet sheet = workbook.createSheet(SHEET_SAMPLE);
		CellStyle headStyle = headerStyle(workbook, false);

		Row hint = sheet.createRow(0);
		hint.createCell(0).setCellValue(type == GoodsImportTemplateType.UPDATE
				? "示例仅供参考，本页不会被导入。更新模板按「商品编码」匹配已有商品，只填要改的列，其余留空即不修改。"
				: "示例仅供参考，本页不会被导入。要用就整行复制到「" + SHEET_DATA + "」页。"
				+ "第 4 行是单规格；第 5、6 行演示多规格：商品编码相同，整品字段只填第一行。");

		Row header = sheet.createRow(2);
		for (int i = 0; i < columns.size(); i++) {
			GoodsImportColumn column = columns.get(i);
			Cell cell = header.createCell(i);
			cell.setCellValue(required(column, type.getCode()) ? column.getHeader() + "*" : column.getHeader());
			cell.setCellStyle(headStyle);
			sheet.setColumnWidth(i, columnWidth(column));
		}

		List<Map<GoodsImportColumn, String>> samples = type == GoodsImportTemplateType.UPDATE
				? updateSamples() : addSamples();
		for (int r = 0; r < samples.size(); r++) {
			Row row = sheet.createRow(r + 3);
			Map<GoodsImportColumn, String> values = samples.get(r);
			for (int i = 0; i < columns.size(); i++) {
				String value = values.get(columns.get(i));
				if (StringUtils.isNotBlank(value)) {
					row.createCell(i).setCellValue(value);
				}
			}
		}
		sheet.createFreezePane(0, 3);
	}

	/** 新增模板示例：1 行单规格 + 2 行同编码多规格 */
	private List<Map<GoodsImportColumn, String>> addSamples() {
		Map<GoodsImportColumn, String> single = new LinkedHashMap<>();
		// 单规格行直接用各列自带的示例值，保证「说明页示例」与「示例页」永远一致
		for (GoodsImportColumn column : GoodsImportColumn.values()) {
			single.put(column, column.getSample());
		}
		// 规格两列的示例值是给多规格看的，单规格行必须留空，否则示例本身就自相矛盾
		single.put(GoodsImportColumn.SPEC_NAMES, "");
		single.put(GoodsImportColumn.SPEC_VALUES, "");
		single.put(GoodsImportColumn.SHELF, "下架");

		Map<GoodsImportColumn, String> head = new LinkedHashMap<>();
		head.put(GoodsImportColumn.SPU_CODE, "TSHIRT-001");
		head.put(GoodsImportColumn.NAME, "纯棉圆领T恤");
		head.put(GoodsImportColumn.SELL_POINT, "精梳棉，透气不起球");
		head.put(GoodsImportColumn.PIC_URLS, "https://example.com/images/tshirt.png");
		head.put(GoodsImportColumn.SHELF, "下架");
		head.put(GoodsImportColumn.SPEC_NAMES, "颜色|尺码");
		head.put(GoodsImportColumn.SPEC_VALUES, "白色|M");
		head.put(GoodsImportColumn.SALES_PRICE, "19.80");
		head.put(GoodsImportColumn.MARKET_PRICE, "26.00");
		head.put(GoodsImportColumn.STOCK, "200");
		head.put(GoodsImportColumn.WEIGHT, "0.08");

		// 第二个规格行：整品字段一律留空，只填编码 + 规格 + 该规格自己的价格库存
		Map<GoodsImportColumn, String> second = new LinkedHashMap<>();
		second.put(GoodsImportColumn.SPU_CODE, "TSHIRT-001");
		second.put(GoodsImportColumn.SPEC_NAMES, "颜色|尺码");
		second.put(GoodsImportColumn.SPEC_VALUES, "白色|L");
		second.put(GoodsImportColumn.SALES_PRICE, "35.00");
		second.put(GoodsImportColumn.MARKET_PRICE, "46.00");
		second.put(GoodsImportColumn.STOCK, "150");
		second.put(GoodsImportColumn.WEIGHT, "0.15");

		return List.of(single, head, second);
	}

	/** 更新模板示例：只填要改的列，其余留空即不修改 */
	private List<Map<GoodsImportColumn, String>> updateSamples() {
		Map<GoodsImportColumn, String> onlyStock = new LinkedHashMap<>();
		onlyStock.put(GoodsImportColumn.SPU_CODE, "RX-SMGLT-001");
		onlyStock.put(GoodsImportColumn.STOCK, "60");

		Map<GoodsImportColumn, String> priceAndShelf = new LinkedHashMap<>();
		priceAndShelf.put(GoodsImportColumn.SPU_CODE, "OTC-MET-0500");
		priceAndShelf.put(GoodsImportColumn.SPEC_VALUES, "0.5g×30片|盒装");
		priceAndShelf.put(GoodsImportColumn.SALES_PRICE, "18.80");
		priceAndShelf.put(GoodsImportColumn.STOCK, "300");

		Map<GoodsImportColumn, String> another = new LinkedHashMap<>();
		another.put(GoodsImportColumn.SPU_CODE, "OTC-MET-0500");
		another.put(GoodsImportColumn.SPEC_VALUES, "0.5g×60片|盒装");
		another.put(GoodsImportColumn.SALES_PRICE, "33.00");

		return List.of(onlyStock, priceAndShelf, another);
	}

	private void buildHelpSheet(Workbook workbook, GoodsImportTemplateType type, List<GoodsImportColumn> columns) {
		Sheet help = workbook.createSheet(SHEET_HELP);
		CellStyle headStyle = headerStyle(workbook, false);
		CellStyle wrapStyle = workbook.createCellStyle();
		wrapStyle.setWrapText(true);
		wrapStyle.setVerticalAlignment(VerticalAlignment.TOP);

		Row tip = help.createRow(0);
		tip.createCell(0).setCellValue(buildTipText(type));
		help.setColumnWidth(0, 4600);
		help.setColumnWidth(1, 2600);
		help.setColumnWidth(2, 16000);
		help.setColumnWidth(3, 12000);
		help.setColumnWidth(4, 12000);

		Row header = help.createRow(2);
		String[] titles = {"列名", "是否必填", "填写说明", "可选值", "示例"};
		for (int i = 0; i < titles.length; i++) {
			Cell cell = header.createCell(i);
			cell.setCellValue(titles[i]);
			cell.setCellStyle(headStyle);
		}
		int r = 3;
		for (GoodsImportColumn column : columns) {
			Row row = help.createRow(r++);
			row.createCell(0).setCellValue(column.getHeader());
			row.createCell(1).setCellValue(required(column, type.getCode()) ? "必填" : "选填");
			Cell noteCell = row.createCell(2);
			noteCell.setCellValue(column.getNote(type.getCode()));
			noteCell.setCellStyle(wrapStyle);
			row.createCell(3).setCellValue(String.join(" / ", column.getOptionLabels()));
			Cell sampleCell = row.createCell(4);
			sampleCell.setCellValue(column.getSample());
			sampleCell.setCellStyle(wrapStyle);
		}
	}

	private String buildTipText(GoodsImportTemplateType type) {
		StringBuilder sb = new StringBuilder();
		sb.append("填写须知：");
		sb.append("0) 「").append(SHEET_SAMPLE).append("」页有填好的样例，可整行复制到数据页改；")
				.append("1) 只填「").append(SHEET_DATA).append("」页，第 1 行表头请勿改动或删除；");
		sb.append("2) 单次最多 ").append(GoodsImportConstants.MAX_ROWS).append(" 条，文件不超过 10MB；");
		sb.append("3) 商品类目在后台导入弹窗里选择，整批统一，表格里不需要填；");
		sb.append("4) 单规格商品一行一个；多规格商品把「商品编码」填成同一个、逐行填「规格项 / 规格值」，"
				+ "规格项各行须完全一致，商品名称等整品字段只填第一行即可；");
		if (type == GoodsImportTemplateType.UPDATE) {
			sb.append("5) 更新模板按「商品编码」匹配已有商品，留空的单元格表示不修改该字段。");
		} else {
			sb.append("5) 导入的商品仍需走审核，审核通过后才对外可见。");
		}
		return sb.toString();
	}

	private void applyValidation(Workbook workbook, Sheet sheet, GoodsImportColumn column, int columnIndex) {
		// 多选列（销售渠道）用 | 拼多个值，下拉只能单选，加了反而挡住正确填法
		if (column.getType() != GoodsImportColumn.ValueType.OPTION || column.getOptionLabels().isEmpty()) {
			return;
		}
		DataValidationHelper helper = sheet.getDataValidationHelper();
		DataValidationConstraint constraint = helper.createExplicitListConstraint(
				column.getOptionLabels().toArray(new String[0]));
		CellRangeAddressList range = new CellRangeAddressList(1, VALIDATION_ROWS, columnIndex, columnIndex);
		DataValidation validation = helper.createValidation(constraint, range);
		validation.setShowErrorBox(true);
		validation.createErrorBox(column.getHeader(), "请从下拉里选择：" + String.join(" / ", column.getOptionLabels()));
		sheet.addValidationData(validation);
	}

	private CellStyle headerStyle(Workbook workbook, boolean required) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		font.setColor(required ? IndexedColors.DARK_RED.getIndex() : IndexedColors.BLACK.getIndex());
		style.setFont(font);
		style.setAlignment(HorizontalAlignment.CENTER);
		style.setVerticalAlignment(VerticalAlignment.CENTER);
		style.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
		style.setFillPattern(FillPatternType.SOLID_FOREGROUND);
		style.setBorderBottom(BorderStyle.THIN);
		return style;
	}

	private int columnWidth(GoodsImportColumn column) {
		return switch (column.getType()) {
			case LONG_TEXT -> 12000;
			case TEXT -> column.getMaxLength() >= 500 ? 12000 : 5200;
			default -> 3400;
		};
	}

	// ===================== 校验细节 =====================

	/**
	 * @param isHead 是否为组头行。SPU 级必填项只在组头行强制——多规格商品的第 2 行起，
	 *               商品名称 / 主图这些整品级字段本来就该留空（相当于合并单元格），
	 *               在每行都要求填一遍等于逼运营复制粘贴 30 列。
	 */
	private void validateCell(GoodsImportRowDTO row, GoodsImportColumn column, String templateType,
							  String name, List<GoodsImportIssueVO> issues, boolean isHead) {
		String raw = row.get(column.getField());
		if (StringUtils.isBlank(raw)) {
			// 商品编码是分组键，每行都必须有；其余 SPU 级列只认组头
			boolean enforce = column == GoodsImportColumn.SPU_CODE
					|| isHead || column.getScope() != GoodsImportColumn.Scope.SPU;
			if (required(column, templateType) && enforce) {
				issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, column.getHeader(),
						GoodsImportConstants.ISSUE_REQUIRED, "缺少必填项：" + column.getHeader()));
			}
			return;
		}
		if (column.getMaxLength() > 0 && raw.length() > column.getMaxLength()) {
			issues.add(GoodsImportIssueVO.of(row.getRowNo(), name, column.getHeader(),
					GoodsImportConstants.ISSUE_FORMAT,
					column.getHeader() + "超长，最多 " + column.getMaxLength() + " 字"));
			return;
		}
		switch (column.getType()) {
			case INT -> {
				Integer value = parseInt(raw);
				if (value == null) {
					issues.add(formatIssue(row, name, column, "应填整数"));
				} else if (value < 0) {
					issues.add(formatIssue(row, name, column, "不能为负数"));
				}
			}
			case DECIMAL -> {
				BigDecimal value = parseDecimal(raw);
				if (value == null) {
					issues.add(formatIssue(row, name, column, "应填数字"));
				} else if (value.signum() < 0) {
					issues.add(formatIssue(row, name, column, "不能为负数"));
				}
			}
			case OPTION -> {
				if (!column.getOptions().containsKey(raw)) {
					issues.add(formatIssue(row, name, column,
							"只能填 " + String.join(" / ", column.getOptionLabels())));
				}
			}
			case MULTI_OPTION -> {
				for (String token : splitMulti(raw)) {
					if (!column.getOptions().containsKey(token)) {
						issues.add(formatIssue(row, name, column,
								"存在无法识别的值「" + token + "」，只能填 "
										+ String.join(" / ", column.getOptionLabels())));
						break;
					}
				}
			}
			default -> {
				if (column == GoodsImportColumn.PIC_URLS || column == GoodsImportColumn.VIDEO_URL) {
					for (String url : splitMulti(raw)) {
						if (!StringUtils.startsWithAny(url, "http://", "https://")) {
							issues.add(formatIssue(row, name, column, "须填完整的 http/https 地址"));
							break;
						}
					}
				}
			}
		}
	}

	/**
	 * 跨字段的条件必填。这些规则与商品新增表单里的 avue control 一一对应，
	 * 漏校验的话会在落库时被 DB 非空约束打成「其他」类失败，运营看不懂原因。
	 */
	private void validateCrossField(GoodsImportRowDTO row, String templateType, String name,
									List<GoodsImportIssueVO> issues, boolean isHead) {
		// 市场价 / 销售价是 SKU 级，多规格的每一行都要判
		BigDecimal sales = parseDecimal(row.get(GoodsImportColumn.SALES_PRICE.getField()));
		BigDecimal market = parseDecimal(row.get(GoodsImportColumn.MARKET_PRICE.getField()));
		if (sales != null && market != null && market.compareTo(sales) < 0) {
			issues.add(formatIssue(row, name, GoodsImportColumn.MARKET_PRICE, "市场价不能低于销售价"));
		}
	}

	private GoodsImportIssueVO formatIssue(GoodsImportRowDTO row, String name, GoodsImportColumn column, String tail) {
		return GoodsImportIssueVO.of(row.getRowNo(), name, column.getHeader(),
				GoodsImportConstants.ISSUE_FORMAT, column.getHeader() + tail);
	}

	private void assertRequiredHeaders(GoodsImportTemplateType type, java.util.Collection<GoodsImportColumn> found) {
		List<String> missing = new ArrayList<>();
		for (GoodsImportColumn column : GoodsImportColumn.columnsOf(type.getCode())) {
			if (required(column, type.getCode()) && !found.contains(column)) {
				missing.add(column.getHeader());
			}
		}
		if (!missing.isEmpty()) {
			throw new BizException("表头缺少必填列：" + String.join("、", missing) + "，请重新下载模板填写");
		}
	}

	// ===================== 小工具 =====================

	private static String describeIssue(GoodsImportIssueVO issue) {
		return StringUtils.isBlank(issue.getHeader())
				? StringUtils.defaultString(issue.getReason())
				: issue.getHeader() + "：" + StringUtils.defaultString(issue.getReason());
	}

	/** 多值列统一按 | 切；英文逗号先归一成 |（老模板的图片列就是逗号分隔） */
	public static List<String> splitMulti(String raw) {
		List<String> list = new ArrayList<>();
		if (StringUtils.isBlank(raw)) {
			return list;
		}
		for (String token : raw.replace("，", "|").replace(",", "|").split("\\|")) {
			String trimmed = token.trim();
			if (StringUtils.isNotBlank(trimmed)) {
				list.add(trimmed);
			}
		}
		return list;
	}

	public static Integer parseInt(String raw) {
		BigDecimal decimal = parseDecimal(raw);
		if (decimal == null) {
			return null;
		}
		try {
			return decimal.intValueExact();
		} catch (ArithmeticException e) {
			return null;
		}
	}

	public static BigDecimal parseDecimal(String raw) {
		if (StringUtils.isBlank(raw)) {
			return null;
		}
		try {
			return new BigDecimal(raw.trim());
		} catch (NumberFormatException e) {
			return null;
		}
	}

	/**
	 * 单元格转文本。数字列走 DataFormatter 以避免 100 被读成 "100.0"；
	 * 公式单元格取缓存结果，不做求值（模板里本来也不该有公式）。
	 */
	private static String cellText(Cell cell) {
		if (cell == null) {
			return "";
		}
		if (cell.getCellType() == CellType.FORMULA) {
			try {
				return FORMATTER.formatCellValue(cell).trim();
			} catch (Exception e) {
				return "";
			}
		}
		return FORMATTER.formatCellValue(cell);
	}
}
