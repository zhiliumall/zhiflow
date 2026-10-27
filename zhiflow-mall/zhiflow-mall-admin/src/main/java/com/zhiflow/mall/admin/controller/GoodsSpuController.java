/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.controller;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.mall.admin.service.*;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.entity.*;
import com.zhiflow.mall.common.utils.SpecNameExtractor;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/goodsspu")
@Tag(description = "goodsspu", name = "spu商品管理")
public class GoodsSpuController {

	private final GoodsSpuService goodsSpuService;
	private final GoodsSkuService goodsSkuService;
	private final GoodsSkuSpecValueService goodsSkuSpecValueService;
	private final ObjectProvider<MeiliSearchSyncService> meiliSearchSyncService;

    /**
    * 分页查询
    * @param page 分页对象
    * @param goodsSpu spu商品
    * @return
    */
	@Operation(summary = "分页查询")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R getPage(Page page, GoodsSpu goodsSpu) {
		IPage<GoodsSpu> pageRs = goodsSpuService.page1(page, goodsSpu);

		List<GoodsSpu> records = pageRs.getRecords();
		if (CollectionUtil.isNotEmpty(records)) {
			List<String> spuIds = records.stream().map(GoodsSpu::getId).distinct().toList();

			// 规格摘要（批量，无 N+1）：
			// ① 批量取本页 SPU 的全部 SKU，按 spuId 取首个 SKU；
			// ② 用首个 SKU 集合一次批量查 goods_sku_spec_value，按 skuId 分组后规格值以 × 拼接；
			// ③ 单规格商品库中无 goods_sku_spec_value → 从商品名启发式兜底解析。
			Map<String, String> spuSpecSummary = new HashMap<>();
			List<GoodsSku> skuList = goodsSkuService.list(Wrappers.query(new GoodsSku()).lambda().in(GoodsSku::getSpuId, spuIds));
			Map<String, String> firstSkuIdOfSpu = new LinkedHashMap<>();
			for (GoodsSku sku : skuList) {
				firstSkuIdOfSpu.putIfAbsent(sku.getSpuId(), sku.getId());
			}
			if (!firstSkuIdOfSpu.isEmpty()) {
				Map<String, String> skuIdToSpuId = new HashMap<>();
				firstSkuIdOfSpu.forEach((spuId, skuId) -> skuIdToSpuId.put(skuId, spuId));
				Map<String, List<GoodsSkuSpecValue>> specBySkuId = goodsSkuSpecValueService
						.listGoodsSkuSpecValueBySkuId(new ArrayList<>(firstSkuIdOfSpu.values()))
						.stream()
						.collect(Collectors.groupingBy(GoodsSkuSpecValue::getSkuId, LinkedHashMap::new, Collectors.toList()));
				specBySkuId.forEach((skuId, values) -> {
					String summary = values.stream()
							.map(GoodsSkuSpecValue::getSpecValueName)
							.filter(Objects::nonNull)
							.collect(Collectors.joining("×"));
					String spuId = skuIdToSpuId.get(skuId);
					if (spuId != null) {
						spuSpecSummary.put(spuId, summary);
					}
				});
			}

			records.forEach(t -> {
				String summary = spuSpecSummary.get(t.getId());
				if (summary == null || summary.isBlank()) {
					summary = SpecNameExtractor.fromProductName(t.getName());
				}
				t.setSpecSummary(summary);
			});
		}

		return R.ok(pageRs);
	}

	/**
	 * list查询
	 * @param goodsSpu
	 * @return
	 */
	@Operation(summary = "list查询")
	@GetMapping("/list")
	public List<GoodsSpu> getList(GoodsSpu goodsSpu) {
		return goodsSpuService.list(Wrappers.query(goodsSpu).lambda()
				.select(GoodsSpu::getId,
						GoodsSpu::getName,
						GoodsSpu::getShopId)
		);
	}

	/**
	 * 查询数量
	 * @param goodsSpu
	 * @return
	 */
	@Operation(summary = "查询数量")
	@GetMapping("/count")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:count')")
	public R getCount(GoodsSpu goodsSpu) {
		return R.ok(goodsSpuService.count(Wrappers.query(goodsSpu)));
	}

    /**
    * 通过id查询spu商品
    * @param id
    * @return R
    */
	@Operation(summary = "通过id查询spu商品")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:get')")
	public R getById(@PathVariable("id") String id){
		GoodsSpu goodsSpu = goodsSpuService.getById1(id);
		if (goodsSpu == null) {
			return R.failed("商品不存在");
		}
		return R.ok(goodsSpu);
    }

    /**
    * 新增spu商品
    * @param goodsSpu spu商品
    * @return R
    */
	@Operation(summary = "新增spu商品")
    @SysLog("新增spu商品")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
    public R save(@RequestBody GoodsSpu goodsSpu){
		try {
			return R.ok(goodsSpuService.save1(goodsSpu));
		}catch (DuplicateKeyException e){
			return duplicateKeyFailed("新增商品", e);
		}
    }

	/**
	 * 批量新增spu商品
	 * @param listGoodsSpu spu商品集合
	 * @return R
	 */
	@Operation(summary = "批量新增spu商品")
	@SysLog("批量新增spu商品")
	@PostMapping("/batch")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:add')")
	public R saveBatch(@RequestBody List<GoodsSpu> listGoodsSpu){
		try {
			return goodsSpuService.saveBatch1(listGoodsSpu);
		}catch (DuplicateKeyException e){
			return duplicateKeyFailed("批量新增商品", e);
		}
	}

    /**
    * 修改spu商品
    * @param goodsSpu spu商品
    * @return R
    */
	@Operation(summary = "修改spu商品")
    @SysLog("修改spu商品")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:edit')")
    public R updateById(@RequestBody GoodsSpu goodsSpu){
		// 单店铺无审核流程：保存即过审，编辑后不能退回「审核中」，否则商品从前台消失
		goodsSpu.setVerifyStatus(MallConstants.GOODS_STATUS_1);
		try {
			return R.ok(goodsSpuService.updateById1(goodsSpu));
		}catch (DuplicateKeyException e){
			return duplicateKeyFailed("修改商品", e);
		}
    }

	/**
	 * 修改spu商品
	 * @param goodsSpu spu商品
	 * @return R
	 */
	@Operation(summary = "修改spu商品")
	@SysLog("修改spu商品")
	@PutMapping("/update")
	// [R6 批3 2026-08-17] 本类另有 4 个方法已挂 mall:goodsspu:edit，此处漏了；8 个角色含超管
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:edit')")
	public R update(@RequestBody GoodsSpu goodsSpu){
		try {
			return R.ok(goodsSpuService.updateById2(goodsSpu));
		}catch (DuplicateKeyException e){
			return duplicateKeyFailed("修改商品", e);
		}
	}

	/**
	 * DuplicateKeyException 的约束名只存在于异常消息里。旧代码 catch 之后直接返回
	 * 「XX失败，请稍后重试」，把约束名一起吞掉：2026-08-08 排查商品编辑失败时，后端
	 * system-error.log 里一个字都没有，只能去 PostgreSQL 服务端日志捞 goods_sku_pkey。
	 * 这里统一落 error 日志，并把已知约束名翻成运营看得懂的话。
	 */
	private R duplicateKeyFailed(String action, DuplicateKeyException e) {
		log.error("{}唯一约束冲突: {}", action, e.getMessage(), e);
		String message = StringUtils.defaultString(e.getMessage());
		if (message.contains("uk_code")) {
			return R.failed("商品编码已存在");
		}
		if (message.contains("goods_sku_pkey")) {
			return R.failed("商品规格数据冲突，请刷新页面后重新提交");
		}
		return R.failed(action + "失败，请稍后重试");
	}

	/**
	 * 商品上下架操作
	 * @param shelf
	 * @param ids
	 * @return R
	 */
	@Operation(summary = "商品上下架操作")
	@SysLog("商品上下架操作")
	@PutMapping("/shelf")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:edit')")
	public R shelf(@RequestParam(value = "shelf") String shelf, @RequestParam(value = "ids") String ids){
		if (StrUtil.isBlank(ids)) {
			return R.failed("请选择需要操作的记录");
		}
		goodsSpuService.shelf(shelf, ids);
		return R.ok();
	}

    /**
    * 通过id删除spu商品
    * @param id
    * @return R
    */
	@Operation(summary = "通过id删除spu商品")
    @SysLog("删除spu商品")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('mall:goodsspu:del')")
    public R removeById(@PathVariable String id){
        return R.ok(goodsSpuService.removeById(id));
    }

	/**
	 * 查询统计
	 * @param goodsSpu
	 * @return
	 */
	@Operation(summary = "查询统计")
	@GetMapping("/statistics")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:statistics')")
	public R getStatistics(GoodsSpu goodsSpu) {
		//总数量
		long countTotal = goodsSpuService.count(Wrappers.query(goodsSpu));
		//今天数量
		LocalDate localDate = LocalDateTime.now().toLocalDate();
		long countToday = goodsSpuService.count(Wrappers.query(goodsSpu).between("create_time", LocalDateTime.of(localDate, LocalTime.MIN), LocalDateTime.of(localDate, LocalTime.MAX)));
		Map<String, Object> rs = new HashMap<>();
		rs.put("countTotal",countTotal);
		rs.put("countToday",countToday);
		return R.ok(rs);
	}

	/**
	 * 重建搜索索引：把当前租户的全部商品重新写进 MeiliSearch，并删掉库里已不存在的文档。
	 * 商品改了但前台搜不到时的兜底入口；未启用 MeiliSearch 时主搜查库，这里直接返回提示。
	 * @return R
	 */
	@Operation(summary = "重建搜索索引")
	@SysLog("重建搜索索引")
	@PostMapping("/meilisearch-sync")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:edit')")
	public R meiliSearchSync() throws Exception {
		MeiliSearchSyncService syncService = meiliSearchSyncService.getIfAvailable();
		if (syncService == null) {
			return R.failed("未启用 MeiliSearch，商品搜索直接查库，无需同步");
		}
		int count = syncService.fullSyncByTenantId(TenantContextHolder.getTenantId());
		return R.ok("同步完成，共 " + count + " 个商品");
	}

	/**
	 * list查询通过ids
	 * @param ids ids
	 * @return
	 */
	@Operation(summary = "list查询通过ids")
	// 只读查询，POST 只为承载 id 列表：不做操作审计（见 ArchExemptions.READ_ONLY_POST_METHODS）
	@PostMapping("/listbyids")
	@PreAuthorize("@ato.hasAuthority('mall:goodsspu:index')")
	public R getGoodsSpuListByIds(@RequestBody List<String> ids) {
		return R.ok(
				goodsSpuService.list(Wrappers.<GoodsSpu>query().lambda()
						.select(GoodsSpu.class, info -> !info.getColumn().equals("description"))
						.in(GoodsSpu::getId, ids))
		);
	}
}
