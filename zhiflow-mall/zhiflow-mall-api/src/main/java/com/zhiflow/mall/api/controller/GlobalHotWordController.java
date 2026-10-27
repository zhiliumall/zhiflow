/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import cn.hutool.core.collection.CollUtil;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.mall.api.service.GlobalHotWordService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;

import java.util.List;

/**
 * 全局热词
 *
 * @date 2025-07-26 21:41:43
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/globalhotword")
@Tag(description = "globalhotword", name = "全局热词管理")
public class GlobalHotWordController {

    private final GlobalHotWordService globalHotWordService;

    /**
     * 热词 Top10（搜索页展示）。热词维护在后台 /mall/hotword。
     * @return R
     */
    @Operation(summary = "获取热词Top10")
    @GetMapping("/getTop")
    public R getTop() {
        return R.ok(globalHotWordService.listTop10());
    }
}
