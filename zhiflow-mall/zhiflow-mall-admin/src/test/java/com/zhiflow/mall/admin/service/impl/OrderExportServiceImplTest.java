package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.api.upms.SysDictApi;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.BatchResult;
import com.zhiflow.common.core.util.R;
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
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockMultipartFile;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 订单导出与 Excel 批量发货。只 mock 接口；{@link ExpressCompanyDict} 用真对象接一个 mock 的 {@link SysDictApi}。
 */
class OrderExportServiceImplTest {

	private static final Map<String, String> COMPANIES = Map.of("shunfeng", "顺丰速运", "zhongtong", "中通快递");

	private OrderInfoService orderInfoService;

	private OrderExportServiceImpl service;

	@BeforeEach
	void setUp() {
		SysDictApi dictApi = mock(SysDictApi.class);
		List<SysDictValueInfo> values = new ArrayList<>();
		COMPANIES.forEach((code, name) -> {
			SysDictValueInfo v = new SysDictValueInfo();
			v.setValue(code);
			v.setLabel(name);
			values.add(v);
		});
		when(dictApi.getInsideDictByType(anyString(), anyString())).thenReturn(R.ok(values));
		orderInfoService = mock(OrderInfoService.class);
		service = new OrderExportServiceImpl(mock(OrderInfoMapper.class), mock(OrderItemMapper.class),
				mock(OrderLogisticsService.class), orderInfoService, new ExpressCompanyDict(dictApi));
	}

	@Test
	@DisplayName("快递公司：填编码或名称都认，名称去首尾空格；认不出返回 null")
	void resolveCompany() {
		assertEquals("shunfeng", ExpressCompanyDict.resolveCode(COMPANIES, "shunfeng"));
		assertEquals("shunfeng", ExpressCompanyDict.resolveCode(COMPANIES, " 顺丰速运 "));
		assertNull(ExpressCompanyDict.resolveCode(COMPANIES, "顺丰"));
		assertNull(ExpressCompanyDict.resolveCode(COMPANIES, " "));
	}

	@Test
	@DisplayName("解析：按表头名找列（顺序不限、可带 *），跳过空行，行号是 Excel 里看到的行号")
	void parseByHeaderName() throws Exception {
		Sheet sheet = sheet(new String[] { "运单号", "订单号*", "物流公司" },
				new String[] { "SF001", "O1", "顺丰速运" }, new String[] { "", "", "" }, new String[] { "ZT9", "O2", "zhongtong" });

		List<OrderExportServiceImpl.DeliveryRow> rows = OrderExportServiceImpl.parseDeliverySheet(sheet);

		assertEquals(List.of(new OrderExportServiceImpl.DeliveryRow(2, "O1", "顺丰速运", "SF001"),
				new OrderExportServiceImpl.DeliveryRow(4, "O2", "zhongtong", "ZT9")), rows);
	}

	@Test
	@DisplayName("解析：长数字订单号按文本取，不变成科学计数法")
	void longNumericOrderNoStaysText() throws Exception {
		Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet();
		Row header = sheet.createRow(0);
		header.createCell(0).setCellValue("订单号");
		header.createCell(1).setCellValue("物流公司");
		header.createCell(2).setCellValue("运单号");
		Row row = sheet.createRow(1);
		row.createCell(0).setCellValue("202610201234567890");
		row.createCell(1).setCellValue("顺丰速运");
		row.createCell(2).setCellValue("SF1");

		assertEquals("202610201234567890", OrderExportServiceImpl.parseDeliverySheet(sheet).getFirst().orderNo());
	}

	@Test
	@DisplayName("整份拒收：缺表头列、没有数据、超过行数上限")
	void wholeFileRejections() throws Exception {
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliverySheet(
				sheet(new String[] { "订单号", "运单号" }, new String[] { "O1", "SF1" })));
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliverySheet(
				sheet(new String[] { "订单号", "物流公司", "运单号" })));
		String[][] many = new String[OrderExportService.MAX_DELIVERY_ROWS + 1][];
		for (int i = 0; i < many.length; i++) {
			many[i] = new String[] { "O" + i, "顺丰速运", "SF" + i };
		}
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliverySheet(
				sheet(new String[] { "订单号", "物流公司", "运单号" }, many)));
	}

	@Test
	@DisplayName("文件校验：扩展名、空文件、读不出来")
	void fileRejections() {
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliveryFile(
				new MockMultipartFile("file", "a.csv", null, "x".getBytes())));
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliveryFile(
				new MockMultipartFile("file", "a.xlsx", null, new byte[0])));
		assertThrows(BizException.class, () -> OrderExportServiceImpl.parseDeliveryFile(
				new MockMultipartFile("file", "a.xlsx", null, "not excel".getBytes())));
	}

	@Test
	@DisplayName("行校验：订单号 / 物流公司 / 运单号为空、公司不在字典、文件内重复")
	void rowChecks() {
		var seen = new HashSet<String>();
		assertTrue(OrderExportServiceImpl.checkRow(row(" ", "顺丰速运", "SF"), COMPANIES, seen).contains("订单号为空"));
		assertTrue(OrderExportServiceImpl.checkRow(row("O1", "", "SF"), COMPANIES, seen).contains("物流公司为空"));
		assertTrue(OrderExportServiceImpl.checkRow(row("O1", "顺丰", "SF"), COMPANIES, seen).contains("不在快递公司字典"));
		assertTrue(OrderExportServiceImpl.checkRow(row("O1", "顺丰速运", ""), COMPANIES, seen).contains("运单号为空"));
		assertNull(OrderExportServiceImpl.checkRow(row("O1", "顺丰速运", "SF"), COMPANIES, seen));
		assertTrue(OrderExportServiceImpl.checkRow(row("O1", "中通快递", "ZT"), COMPANIES, seen).contains("重复"));
	}

	@Test
	@DisplayName("批量发货：逐行调单笔发货，填名称也换成编码；单行被拒、订单不存在、意外异常都只记这一行")
	void batchDeliveryRowByRow() throws Exception {
		OrderInfo o1 = order("id1", "O1");
		OrderInfo o3 = order("id3", "O3");
		OrderInfo o4 = order("id4", "O4");
		when(orderInfoService.getOne(any())).thenReturn(o1, null, o3, o4);
		doThrow(new BizException("订单未付款，不能发货"))
				.when(orderInfoService).delivery(org.mockito.ArgumentMatchers.argThat(d -> d != null && "id3".equals(d.getOrderId())));
		doThrow(new IllegalStateException("db down"))
				.when(orderInfoService).delivery(org.mockito.ArgumentMatchers.argThat(d -> d != null && "id4".equals(d.getOrderId())));

		BatchResult<String> result = service.batchDelivery(file(new String[] { "订单号", "物流公司", "运单号" },
				new String[] { "O1", "顺丰速运", "SF1" }, new String[] { "O2", "shunfeng", "SF2" },
				new String[] { "O3", "zhongtong", "ZT3" }, new String[] { "O4", "zhongtong", "ZT4" },
				new String[] { "O5", "不认识的快递", "X5" }));

		assertEquals(5, result.getTotal());
		assertEquals(List.of("第2行"), result.getSucceeded());
		assertEquals(4, result.getFailCount());
		assertTrue(!result.isUnaccounted());
		assertEquals(List.of("ORDER_NOT_FOUND", "DELIVERY_REJECTED", "SYSTEM_ERROR", "INVALID_ROW"),
				result.getFailed().stream().map(BatchResult.Failure::getCode).toList());

		ArgumentCaptor<OrderDeliveryDTO> sent = ArgumentCaptor.forClass(OrderDeliveryDTO.class);
		verify(orderInfoService, times(3)).delivery(sent.capture());
		OrderDeliveryDTO first = sent.getAllValues().getFirst();
		assertEquals("shunfeng", first.getLogisticsCode());
		assertEquals(MallConstants.DELIVERY_TYPE_1, first.getDeliveryType());
		assertEquals("收货人", first.getRecName());
	}

	@Test
	@DisplayName("批量发货：字典为空时整份拒收，不逐行报一堆「不在字典」")
	void emptyDictRejectsWholeFile() throws Exception {
		SysDictApi empty = mock(SysDictApi.class);
		when(empty.getInsideDictByType(anyString(), anyString())).thenReturn(R.ok(List.of()));
		OrderExportServiceImpl noDict = new OrderExportServiceImpl(mock(OrderInfoMapper.class), mock(OrderItemMapper.class),
				mock(OrderLogisticsService.class), orderInfoService, new ExpressCompanyDict(empty));

		assertThrows(BizException.class, () -> noDict.batchDelivery(
				file(new String[] { "订单号", "物流公司", "运单号" }, new String[] { "O1", "顺丰速运", "SF1" })));
		verify(orderInfoService, never()).delivery(any());
	}

	@Test
	@DisplayName("导出行：多商品拼一格、件数求和、多次发货的物流用「；」分隔")
	void exportRow() {
		OrderInfo o = order("id1", "O1");
		o.setCreateTime(LocalDateTime.of(2026, 10, 20, 9, 30));
		o.setPaymentPrice(new BigDecimal("98.50"));
		o.setPaymentType("1");
		OrderItem a = new OrderItem();
		a.setSpuName("T 恤");
		a.setSpecInfo("白色,L");
		a.setQuantity(2);
		OrderItem b = new OrderItem();
		b.setSpuName("帽子");
		b.setQuantity(1);
		OrderLogistics l1 = new OrderLogistics();
		l1.setLogisticsName("顺丰速运");
		l1.setLogisticsNo("SF1");
		OrderLogistics l2 = new OrderLogistics();
		l2.setLogisticsName("中通快递");
		l2.setLogisticsNo("ZT2");

		List<String> row = OrderExportServiceImpl.exportRow(o, List.of(a, b), List.of(l1, l2));

		assertEquals("O1", row.get(0));
		assertEquals("2026-10-20 09:30:00", row.get(1));
		assertEquals("T 恤（白色,L） ×2；帽子 ×1", row.get(6));
		assertEquals("3", row.get(7));
		assertEquals("98.50", row.get(10));
		assertEquals("微信支付", row.get(11));
		assertEquals("顺丰速运；中通快递", row.get(13));
		assertEquals("SF1；ZT2", row.get(14));
	}

	@Test
	@DisplayName("模板：两张表，第二张列出字典里的物流公司")
	void template() throws Exception {
		try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(service.deliveryTemplate()))) {
			assertEquals("订单号", wb.getSheetAt(0).getRow(0).getCell(0).getStringCellValue());
			assertEquals(COMPANIES.size(), wb.getSheetAt(1).getLastRowNum());
		}
	}

	private static OrderExportServiceImpl.DeliveryRow row(String orderNo, String company, String trackingNo) {
		return new OrderExportServiceImpl.DeliveryRow(2, orderNo, company, trackingNo);
	}

	private static OrderInfo order(String id, String orderNo) {
		OrderInfo o = new OrderInfo();
		o.setId(id);
		o.setOrderNo(orderNo);
		o.setRecName("收货人");
		o.setRecTel("13800000000");
		o.setRecAddress("某地");
		return o;
	}

	private static Sheet sheet(String[] header, String[]... rows) {
		Workbook wb = new XSSFWorkbook();
		Sheet sheet = wb.createSheet();
		fill(sheet, header, rows);
		return sheet;
	}

	private static MockMultipartFile file(String[] header, String[]... rows) throws Exception {
		try (Workbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
			fill(wb.createSheet(), header, rows);
			wb.write(out);
			return new MockMultipartFile("file", "发货.xlsx", null, out.toByteArray());
		}
	}

	private static void fill(Sheet sheet, String[] header, String[]... rows) {
		Row h = sheet.createRow(0);
		for (int i = 0; i < header.length; i++) {
			h.createCell(i).setCellValue(header[i]);
		}
		for (int r = 0; r < rows.length; r++) {
			Row row = sheet.createRow(r + 1);
			for (int i = 0; i < rows[r].length; i++) {
				row.createCell(i).setCellValue(rows[r][i]);
			}
		}
	}
}
