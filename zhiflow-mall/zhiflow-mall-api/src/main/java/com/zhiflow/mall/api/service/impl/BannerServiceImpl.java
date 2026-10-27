package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.api.mapper.BannerMapper;
import com.zhiflow.mall.api.service.BannerService;
import com.zhiflow.mall.common.entity.Banner;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BannerServiceImpl extends ServiceImpl<BannerMapper, Banner> implements BannerService {

	/** 首页轮播最多取这么多张，防止后台误配几百张拖慢首页。 */
	static final int MAX_BANNERS = 10;

	@Override
	@Cacheable(value = CacheConstants.MALL_BANNER_CACHE, condition = CacheConstants.TENANT_PRESENT)
	public List<Banner> listEnabled() {
		return list(Wrappers.<Banner>lambdaQuery()
				.select(Banner::getId, Banner::getImageUrl, Banner::getLinkUrl, Banner::getSort)
				.eq(Banner::getEnable, "1")
				.orderByAsc(Banner::getSort)
				.orderByDesc(Banner::getCreateTime)
				.last("limit " + MAX_BANNERS));
	}
}
