package com.zhiflow.mall.admin.service;

import com.zhiflow.mall.common.dto.GoodsImportRowDTO;
import com.zhiflow.mall.common.entity.GoodsImportTask;

import java.util.List;

/**
 * 一个商品（= 同一商品编码的一组行）落库。独立成 bean 是为了让每组走**各自的事务**——
 * 第 300 个商品失败不能把前面 299 个一起回滚，那正是「部分成功」这个状态存在的意义；
 * 而组内必须同事务，多规格商品不存在「导进去一半规格」这种中间态。
 */
public interface GoodsImportRowWriter {

	/**
	 * 写入一个商品。成功返回 true（新建），false 表示按覆盖策略更新了已有商品。
	 * 任何业务性失败都以 {@code BizException} 抛出，消息即运营看到的失败原因。
	 *
	 * @param group 同一商品编码的行，按文件行序；第一行是组头，SPU 级字段只从它读
	 */
	boolean write(GoodsImportTask task, List<GoodsImportRowDTO> group, GoodsImportContext context);
}
