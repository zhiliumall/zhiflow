package com.zhiflow.mall.admin.service;

import com.zhiflow.common.core.util.BatchResult;
import com.zhiflow.mall.common.entity.OrderInfo;
import org.springframework.web.multipart.MultipartFile;

/**
 * 订单导出与 Excel 批量发货（S-12 / N-6）。
 */
public interface OrderExportService {

	/** 单次最多导出的订单数。 */
	int MAX_EXPORT_ORDERS = 10_000;

	/** 单个批量发货文件最多的数据行。 */
	int MAX_DELIVERY_ROWS = 500;

	/**
	 * 按订单列表的同一套筛选条件导出（一单一行，多个商品拼在同一格）。
	 *
	 * @throws com.zhiflow.common.core.exception.BizException 命中超过 {@link #MAX_EXPORT_ORDERS} 单
	 */
	byte[] export(OrderInfo query);

	/** 批量发货模板：订单号、物流公司（编码或名称）、运单号，附一张可选物流公司表。 */
	byte[] deliveryTemplate();

	/**
	 * 按上传的 Excel 逐行发货，每行独立事务，失败行不影响其他行。条目标识为「第 N 行」。
	 *
	 * @throws com.zhiflow.common.core.exception.BizException 文件格式、表头或行数不对（整份拒收）
	 */
	BatchResult<String> batchDelivery(MultipartFile file);
}
