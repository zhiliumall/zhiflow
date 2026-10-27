package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.mall.admin.service.MeiliSearchSyncService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 商品搜索索引 write-through：商品写库的事务提交后，同步把变更写进 MeiliSearch。
 *
 * <p>未启用 MeiliSearch 时什么都不做（主搜查库）。写索引失败只记日志、不回滚业务——
 * 索引是派生数据，后台「同步搜索」按钮可随时重建。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GoodsSearchIndexSync {

	private final ObjectProvider<MeiliSearchSyncService> meiliSearchSyncService;

	/** 事务提交后把商品最新数据写进索引（商品已删除时删文档）。 */
	public void upsertAfterCommit(String goodsId) {
		runAfterCommit(goodsId, false);
	}

	/** 事务提交后从索引删除商品文档。 */
	public void deleteAfterCommit(String goodsId) {
		runAfterCommit(goodsId, true);
	}

	private void runAfterCommit(String goodsId, boolean delete) {
		MeiliSearchSyncService syncService = meiliSearchSyncService.getIfAvailable();
		if (syncService == null || goodsId == null) {
			return;
		}
		// 提交回调里租户上下文可能已被清掉，这里先取好
		String tenantId = TenantContextHolder.getTenantId();
		Runnable task = () -> {
			try {
				if (delete) {
					syncService.deleteOneGoods(tenantId, goodsId);
				}
				else {
					syncService.syncOneGoods(tenantId, goodsId);
				}
			}
			catch (Exception e) {
				log.error("商品写索引失败，可在后台点「同步搜索」重建: tenantId={}, goodsId={}", tenantId, goodsId, e);
			}
		};
		if (TransactionSynchronizationManager.isSynchronizationActive()) {
			TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
				@Override
				public void afterCommit() {
					task.run();
				}
			});
		}
		else {
			task.run();
		}
	}
}
