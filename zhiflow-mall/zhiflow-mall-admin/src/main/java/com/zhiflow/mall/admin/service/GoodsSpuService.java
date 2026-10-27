/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.common.core.util.R;
import com.zhiflow.mall.common.entity.GoodsSpu;

import java.util.List;

/**
 * spu商品
 *
 * @date 2019-08-12 16:25:10
 */
public interface GoodsSpuService extends IService<GoodsSpu> {

	IPage<GoodsSpu> page1(IPage<GoodsSpu> page, GoodsSpu goodsSpu);

	boolean save1(GoodsSpu goodsSpu);

	/**
	 * 批量新增
	 * @param listGoodsSpu
	 * @return
	 */
	R saveBatch1(List<GoodsSpu> listGoodsSpu);

	boolean updateById1(GoodsSpu goodsSpu);
	boolean updateById2(GoodsSpu goodsSpu);

	GoodsSpu getById1(String id);

	/**
	 * 上架
	 * @param shelf
	 * @param ids
	 */
	void shelf(String shelf, String ids);
}
