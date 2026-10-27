/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.common.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.List;

/**
 * spu规格
 *
 * @date 2019-08-13 16:56:46
 */
@Data
public class GoodsSpecVO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

    private String id;

	private String spuId;

    private String value;

    private List<GoodsSpecLeafVO> leaf;

}
