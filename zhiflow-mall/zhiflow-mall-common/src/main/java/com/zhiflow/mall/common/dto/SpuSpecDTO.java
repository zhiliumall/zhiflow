/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 *
 * @date 2019-08-12 16:25:10
 */
@Data
public class SpuSpecDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;
    private String value;

}
