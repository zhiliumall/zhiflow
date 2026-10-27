package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.Banner;

import java.util.List;

/**
 * 首页轮播图（只读）。维护在后台 {@code /mall/banner}。
 */
public interface BannerService extends IService<Banner> {

	/** 启用的轮播图，按排序升序。短 TTL 缓存，后台改完最多一分钟可见。 */
	List<Banner> listEnabled();
}
