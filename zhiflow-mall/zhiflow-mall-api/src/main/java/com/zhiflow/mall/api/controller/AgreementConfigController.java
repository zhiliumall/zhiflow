/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.controller;

import com.zhiflow.api.upms.AgreementConfigApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.annotation.ApiLogin;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 协议管理
 *
 * @date 2024-03-19 15:24:21
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/agreementconfig")
@Tag(description = "agreementconfig", name = "协议API")
public class AgreementConfigController {

    private final AgreementConfigApi agreementConfigApi;
    /**
     * 通过类型查询会员协议
     * @param type
     * @return
     */
    @Operation(summary = "通过类型查询会员协议")
    @ApiLogin(mustLogin = false)
    @GetMapping("/{type}")
    public R getByType(@PathVariable("type") String type) {
        // upms 内部接口已经返回 R，原样透出（不要再包一层）
        return agreementConfigApi.getByType(type, SecurityConstants.FROM_IN);
    }
}
