package com.zhiflow.mall.admin.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.BatchResult;
import com.zhiflow.mall.admin.mapper.OrderInfoMapper;
import com.zhiflow.mall.admin.mapper.OrderItemMapper;
import com.zhiflow.mall.admin.service.OrderExportService;
import com.zhiflow.mall.admin.service.OrderInfoService;
import com.zhiflow.mall.admin.service.OrderLogisticsService;
import com.zhiflow.mall.admin.support.ExpressCompanyDict;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.OrderDeliveryDTO;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderItem;
import com.zhiflow.mall.common.entity.OrderLogistics;
import com.zhiflow.mall.common.enums.PaymentTypeEnum;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.streaming.SXSSFWorkbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 订单导出与 Excel 批量发货（S-12 / N-6）。
 *
 * <p>导出走订单列表同一条查询（{@code OrderInfoMapper.selectPage1}），所以「列表筛出什么就导出什么」。
 * 批量发货逐行调 {@link OrderInfoService#delivery}：经代理调用，每行一个事务，发货规则（是否已付、是否可发、
 * 物流公司是否在字典里）与单笔发货完全一致。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderExportServiceImpl implements OrderExportService {

	static final String COL_ORDER_NO = "订单号";

	static final String COL_COMPANY = "物流公司";

	static final String COL_TRACKING_NO = "运单号";

	static final long MAX_DELIVERY_FILE_BYTES = 2L * 1024 * 1024;

	private static final int PAGE_SIZE = 500;

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");

	private static final DataFormatter FORMATTER = new DataFormatter();

	private static final String[] EXPORT_HEADERS = { "订单号", "下单时间", "订单状态", "收货人", "联系电话", "收货地址",
			"商品", "件数", "商品金额", "运费", "实付金额", "支付方式", "支付时间", "物流公司", "运单号", "买家留言", "商家备注" };

	private final OrderInfoMapper orderInfoMapper;

	private final OrderItemMapper orderItemMapper;

	private final OrderLogisticsService orderLogisticsService;

	private final OrderInfoService orderInfoService;

	private final ExpressCompanyDict expressCompanyDict;

	@Override
	public byte[] export(OrderInfo query) {
		List<OrderInfo> orders = new ArrayList<>();
		long pages = 1;
		for (long current = 1; current <= pages; current++) {
			Page<OrderInfo> page = new Page<>(current, PAGE_SIZE, current == 1);
			page.addOrder(com.baomidou.mybatisplus.core.metadata.OrderItem.desc("create_time"));
			IPage<OrderInfo> result = orderInfoMapper.selectPage1(page, query);
			if (current == 1) {
				if (result.getTotal() > MAX_EXPORT_ORDERS) {
					throw new BizException("符合条件的订单有 " + result.getTotal() + " 单，超过单次导出上限 "
							+ MAX_EXPORT_ORDERS + " 单，请缩小时间范围后分批导出");
				}
				pages = result.getPages();
			}
			orders.addAll(result.getRecords());
		}
		Set<String> orderIds = orders.stream().map(OrderInfo::getId).collect(Collectors.toSet());
		Map<String, List<OrderItem>> items = orderIds.isEmpty() ? Map.of()
				: orderItemMapper.selectListByOrderIds(orderIds).stream()
						.collect(Collectors.groupingBy(OrderItem::getOrderId));
		Map<String, List<OrderLogistics>> logistics = orderIds.isEmpty() ? Map.of()
				: orderLogisticsService.list(Wrappers.<OrderLogistics>lambdaQuery()
						.in(OrderLogistics::getOrderId, orderIds)).stream()
						.collect(Collectors.groupingBy(OrderLogistics::getOrderId));

		SXSSFWorkbook workbook = new SXSSFWorkbook(200);
		try (ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("订单");
			writeHeader(workbook, sheet, EXPORT_HEADERS);
			int r = 1;
			for (OrderInfo order : orders) {
				writeRow(sheet.createRow(r++), exportRow(order, items.getOrDefault(order.getId(), List.of()),
						logistics.getOrDefault(order.getId(), List.of())));
			}
			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new BizException("导出文件生成失败");
		}
		finally {
			try {
				workbook.close();
			}
			catch (IOException ignored) {
				// 临时文件清理失败不影响导出结果
			}
		}
	}

	/** 一单一行；多个商品拼成「名称（规格）×数量」，多次发货的物流用「；」分隔。 */
	static List<String> exportRow(OrderInfo order, List<OrderItem> items, List<OrderLogistics> logistics) {
		String goods = items.stream()
				.map(i -> i.getSpuName() + (StrUtil.isBlank(i.getSpecInfo()) ? "" : "（" + i.getSpecInfo() + "）")
						+ " ×" + (i.getQuantity() == null ? 0 : i.getQuantity()))
				.collect(Collectors.joining("；"));
		int quantity = items.stream().mapToInt(i -> i.getQuantity() == null ? 0 : i.getQuantity()).sum();
		return List.of(
				nvl(order.getOrderNo()),
				time(order.getCreateTime()),
				nvl(order.getStatusDesc()),
				nvl(order.getRecName()),
				nvl(order.getRecTel()),
				nvl(order.getRecAddress()),
				goods,
				String.valueOf(quantity),
				money(order.getSalesPrice()),
				money(order.getFreightPrice()),
				money(order.getPaymentPrice()),
				PaymentTypeEnum.getDesc(order.getPaymentType()),
				time(order.getPaymentTime()),
				logistics.stream().map(OrderLogistics::getLogisticsName).filter(StrUtil::isNotBlank)
						.collect(Collectors.joining("；")),
				logistics.stream().map(OrderLogistics::getLogisticsNo).filter(StrUtil::isNotBlank)
						.collect(Collectors.joining("；")),
				nvl(order.getUserMessage()),
				nvl(order.getMerchantRemark()));
	}

	@Override
	public byte[] deliveryTemplate() {
		try (Workbook workbook = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			Sheet sheet = workbook.createSheet("批量发货");
			writeHeader(workbook, sheet, new String[] { COL_ORDER_NO, COL_COMPANY, COL_TRACKING_NO });
			// 订单号、运单号是长数字：整列设成文本，免得 Excel 把手敲的值转成科学计数法丢掉末几位
			CellStyle text = workbook.createCellStyle();
			text.setDataFormat(workbook.createDataFormat().getFormat("@"));
			sheet.setDefaultColumnStyle(0, text);
			sheet.setDefaultColumnStyle(2, text);
			sheet.setColumnWidth(0, 28 * 256);
			sheet.setColumnWidth(1, 16 * 256);
			sheet.setColumnWidth(2, 28 * 256);
			Sheet companies = workbook.createSheet("可选物流公司");
			writeHeader(workbook, companies, new String[] { "名称", "编码" });
			int r = 1;
			for (Map.Entry<String, String> e : expressCompanyDict.load().entrySet()) {
				writeRow(companies.createRow(r++), List.of(nvl(e.getValue()), e.getKey()));
			}
			companies.setColumnWidth(0, 16 * 256);
			companies.setColumnWidth(1, 20 * 256);
			workbook.write(out);
			return out.toByteArray();
		}
		catch (IOException e) {
			throw new BizException("模板生成失败");
		}
	}

	@Override
	public BatchResult<String> batchDelivery(MultipartFile file) {
		List<DeliveryRow> rows = parseDeliveryFile(file);
		Map<String, String> companies = expressCompanyDict.load();
		if (companies.isEmpty()) {
			throw new BizException("快递公司字典 " + ExpressCompanyDict.DICT_TYPE + " 为空，请先在字典管理里维护");
		}
		BatchResult<String> result = BatchResult.of(rows.size());
		Set<String> seen = new HashSet<>();
		for (DeliveryRow row : rows) {
			String id = "第" + row.rowNumber() + "行";
			String failure = checkRow(row, companies, seen);
			if (failure != null) {
				result.fail(id, "INVALID_ROW", failure);
				continue;
			}
			OrderInfo order = orderInfoService.getOne(Wrappers.<OrderInfo>lambdaQuery()
					.eq(OrderInfo::getOrderNo, row.orderNo()));
			if (order == null) {
				result.fail(id, "ORDER_NOT_FOUND", "订单号 " + row.orderNo() + " 不存在");
				continue;
			}
			OrderDeliveryDTO dto = new OrderDeliveryDTO();
			dto.setOrderId(order.getId());
			dto.setDeliveryType(MallConstants.DELIVERY_TYPE_1);
			dto.setLogisticsCode(ExpressCompanyDict.resolveCode(companies, row.company()));
			dto.setLogisticsNo(row.trackingNo());
			dto.setRecName(order.getRecName());
			dto.setRecTel(order.getRecTel());
			dto.setRecAddress(order.getRecAddress());
			try {
				orderInfoService.delivery(dto);
				result.succeed(id);
			}
			catch (BizException e) {
				result.fail(id, "DELIVERY_REJECTED", "订单号 " + row.orderNo() + "：" + e.getMessage());
			}
			catch (Exception e) {
				// delivery 带 @SneakyThrows，可能抛受检异常；任何意外都只记这一行，不中断整批
				log.error("批量发货失败 orderNo={}", row.orderNo(), e);
				result.fail(id, "SYSTEM_ERROR", "订单号 " + row.orderNo() + "：系统异常，请稍后单独重试");
			}
		}
		return result;
	}

	/** @return 不合法时的原因；合法返回 null。同一订单号只认第一次出现。 */
	static String checkRow(DeliveryRow row, Map<String, String> companies, Set<String> seen) {
		if (StrUtil.isBlank(row.orderNo())) {
			return "订单号为空";
		}
		if (ExpressCompanyDict.resolveCode(companies, row.company()) == null) {
			return StrUtil.isBlank(row.company()) ? "物流公司为空"
					: "物流公司「" + row.company() + "」不在快递公司字典里，模板第二页有可选列表";
		}
		if (StrUtil.isBlank(row.trackingNo())) {
			return "运单号为空";
		}
		if (!seen.add(row.orderNo())) {
			return "订单号 " + row.orderNo() + " 在文件里重复出现，只处理第一次";
		}
		return null;
	}

	/** 整份文件的校验：格式、大小、表头、行数。逐行的问题留给 {@link #checkRow}。 */
	static List<DeliveryRow> parseDeliveryFile(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BizException("请上传批量发货文件");
		}
		String name = StrUtil.blankToDefault(file.getOriginalFilename(), "").toLowerCase();
		if (!name.endsWith(".xlsx") && !name.endsWith(".xls")) {
			throw new BizException("只支持 .xlsx / .xls 文件");
		}
		if (file.getSize() > MAX_DELIVERY_FILE_BYTES) {
			throw new BizException("文件不能超过 2MB");
		}
		try (InputStream in = file.getInputStream(); Workbook workbook = WorkbookFactory.create(in)) {
			return parseDeliverySheet(workbook.getSheetAt(0));
		}
		catch (IOException | RuntimeException e) {
			if (e instanceof BizException biz) {
				throw biz;
			}
			throw new BizException("文件读不出来，请用批量发货模板填写后上传");
		}
	}

	static List<DeliveryRow> parseDeliverySheet(Sheet sheet) {
		Row header = sheet.getRow(sheet.getFirstRowNum());
		Map<String, Integer> columns = new HashMap<>();
		if (header != null) {
			for (Cell cell : header) {
				columns.putIfAbsent(StrUtil.trim(FORMATTER.formatCellValue(cell)).replace("*", ""), cell.getColumnIndex());
			}
		}
		for (String required : List.of(COL_ORDER_NO, COL_COMPANY, COL_TRACKING_NO)) {
			if (!columns.containsKey(required)) {
				throw new BizException("表头缺少「" + required + "」列，请用批量发货模板填写");
			}
		}
		List<DeliveryRow> rows = new ArrayList<>();
		for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
			Row row = sheet.getRow(r);
			String orderNo = text(row, columns.get(COL_ORDER_NO));
			String company = text(row, columns.get(COL_COMPANY));
			String trackingNo = text(row, columns.get(COL_TRACKING_NO));
			if (StrUtil.isAllBlank(orderNo, company, trackingNo)) {
				continue;
			}
			if (rows.size() == MAX_DELIVERY_ROWS) {
				throw new BizException("一次最多 " + MAX_DELIVERY_ROWS + " 行，请拆成多个文件上传");
			}
			rows.add(new DeliveryRow(r + 1, orderNo, company, trackingNo));
		}
		if (rows.isEmpty()) {
			throw new BizException("文件里没有要发货的数据");
		}
		return rows;
	}

	/** 单元格按 Excel 里看到的样子取文本（订单号是长数字，不能被当成科学计数法的数值）。 */
	private static String text(Row row, Integer column) {
		if (row == null || column == null) {
			return null;
		}
		Cell cell = row.getCell(column);
		return cell == null ? null : StrUtil.trim(FORMATTER.formatCellValue(cell));
	}

	/** Excel 行号（从 1 开始，含表头）与三列原值。 */
	record DeliveryRow(int rowNumber, String orderNo, String company, String trackingNo) {
	}

	private static void writeHeader(Workbook workbook, Sheet sheet, String[] headers) {
		CellStyle style = workbook.createCellStyle();
		Font font = workbook.createFont();
		font.setBold(true);
		style.setFont(font);
		Row row = sheet.createRow(0);
		for (int i = 0; i < headers.length; i++) {
			Cell cell = row.createCell(i);
			cell.setCellValue(headers[i]);
			cell.setCellStyle(style);
		}
	}

	private static void writeRow(Row row, List<String> values) {
		for (int i = 0; i < values.size(); i++) {
			row.createCell(i).setCellValue(values.get(i));
		}
	}

	private static String nvl(String value) {
		return value == null ? "" : value;
	}

	private static String time(LocalDateTime value) {
		return value == null ? "" : value.format(TIME);
	}

	private static String money(BigDecimal value) {
		return value == null ? "" : value.toPlainString();
	}
}
