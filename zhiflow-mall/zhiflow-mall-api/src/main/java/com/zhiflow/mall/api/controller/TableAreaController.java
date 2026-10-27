/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.api.upms.TableAreaApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.upms.common.dto.AreaQuery;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.concurrent.TimeUnit;

/**
 * 地区表
 *
 * @date 2023-10-10 15:51:20
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/tablearea")
@Tag(description = "tablearea", name = "地区表api")
public class TableAreaController {

    private final TableAreaApi tableAreaApi;

    // perf 2026-09-15：/tablearea/list 原每次请求同步 Feign 调 upms，地区数据近乎静态（绝大多数
    // 调用按 pid 做级联选择）；按租户+完整查询条件（AreaQuery 任一非空字段都会拼进 WHERE，见该类
    // 注释，故用整个 query 的 toString 而非只用 pid 做键，避免不同过滤条件撞键）做 60s TTL 缓存。
    private static final Cache<String, R> TABLE_AREA_CACHE = Caffeine.newBuilder()
            .expireAfterWrite(60, TimeUnit.SECONDS)
            .maximumSize(2000)
            .build();

    /**
     * 地区表分页List
     * @param tableArea 地区表
     * @return
     */
    @Operation(summary = "地区表分页List")
    @GetMapping("/list")
    public R getList(AreaQuery tableArea) {
        String cacheKey = TenantContextHolder.getTenantId() + ":" + tableArea;
        R cached = TABLE_AREA_CACHE.getIfPresent(cacheKey);
        if (cached != null) {
            return cached;
        }
        // upms 内部接口已经返回 R，原样透出（不要再包一层）；只缓存成功结果
        R result = tableAreaApi.getList(tableArea, SecurityConstants.FROM_IN);
        if (result != null && Boolean.TRUE.equals(result.isOk())) {
            TABLE_AREA_CACHE.put(cacheKey, result);
        }
        return result;
    }
}
