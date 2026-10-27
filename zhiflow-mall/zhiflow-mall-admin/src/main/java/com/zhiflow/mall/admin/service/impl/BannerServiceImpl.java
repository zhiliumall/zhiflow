package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.BannerMapper;
import com.zhiflow.mall.admin.service.BannerService;
import com.zhiflow.mall.common.entity.Banner;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 首页轮播。增删改之后清用户端轮播缓存（{@code mall:banner:enabled#60}，读方是 mall-api BannerServiceImpl#listEnabled，
 * 无参方法的键是 {@code SimpleKey []}），按轮播行的 tenant_id 解析缓存，有事务时推迟到提交后。
 */
@Service
@RequiredArgsConstructor
public class BannerServiceImpl extends ServiceImpl<BannerMapper, Banner> implements BannerService {

	private final CacheManager cacheManager;

	/** 刚插入的行 tenant_id 可能只在 SQL 里（租户插件写的），实体上为空时按当前租户清——插件写进去的正是当前租户。 */
	@Override
	public boolean save(Banner entity) {
		boolean saved = super.save(entity);
		evictAfterCommit(entity.getTenantId());
		return saved;
	}

	@Override
	public boolean updateById(Banner entity) {
		boolean updated = super.updateById(entity);
		Banner row = entity.getId() == null ? null : baseMapper.selectById(entity.getId());
		evictAfterCommit(row == null ? entity.getTenantId() : row.getTenantId());
		return updated;
	}

	@Override
	public boolean removeById(Serializable id) {
		// 删之前查：删掉之后就查不到轮播行（和它的 tenant_id）了
		Banner row = baseMapper.selectById(id);
		boolean removed = super.removeById(id);
		if (row != null) {
			evictAfterCommit(row.getTenantId());
		}
		return removed;
	}

	private void evictAfterCommit(String tenantId) {
		AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_BANNER_CACHE, SimpleKey.EMPTY);
	}
}
