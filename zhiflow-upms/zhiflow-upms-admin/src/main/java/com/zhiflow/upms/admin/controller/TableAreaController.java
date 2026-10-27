/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.common.dto.AreaQuery;
import com.zhiflow.upms.common.dto.AreaInfo;
import java.util.List;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.upms.common.entity.TableArea;
import com.zhiflow.upms.admin.service.TableAreaService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

/**
 * 地区表
 *
 * @date 2023-10-10 15:51:20
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/tablearea")
@Tag(description = "tablearea", name = "地区表管理")
public class TableAreaController {

    private final TableAreaService tableAreaService;

    /**
     * 地区表分页列表
     * @param page 分页对象
     * @param tableArea 地区表
     * @return
     */
    @Operation(summary = "地区表分页列表")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('upms:tablearea:index')")
    public R getPage(Page page, TableArea tableArea) {
        return R.ok(tableAreaService.page(page, Wrappers.query(tableArea)));
    }

    /**
     * 地区表查询
     * @param id
     * @return R
     */
    @Operation(summary = "地区表查询")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('upms:tablearea:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(tableAreaService.getById(id));
    }

    /**
     * 地区表分页List
     * @param tableArea 地区表
     * @return
     */
    @Operation(summary = "地区表分页List")
    @GetMapping("/list")
    public R getList(TableArea tableArea) {
        return R.ok(tableAreaService.list(Wrappers.query(tableArea)));
    }

    /**
     * 地区表分页List（服务间调用）
     * @param tableArea 地区表
     * @return
     */
    @Operation(summary = "地区表分页List（服务间调用）")
    @PostMapping("/inside/list")
    @Inside
    public R<List<AreaInfo>> getInsideList(@RequestBody AreaQuery tableArea) {
        return R.ok(UpmsInsideProjections
                .areas(tableAreaService.list(Wrappers.query(UpmsInsideProjections.areaQuery(tableArea)))));
    }
}
