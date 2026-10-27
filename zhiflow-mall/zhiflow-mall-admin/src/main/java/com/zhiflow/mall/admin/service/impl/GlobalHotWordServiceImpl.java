package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.mall.admin.mapper.GlobalHotWordMapper;
import com.zhiflow.mall.admin.service.GlobalHotWordService;
import com.zhiflow.mall.common.entity.GlobalHotWord;
import lombok.RequiredArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.interceptor.SimpleKey;
import org.springframework.stereotype.Service;

import java.io.Serializable;

/**
 * 搜索页热搜词。增删改之后清用户端热词缓存（{@code mall:hotword:top#60}，读方是 mall-api GlobalHotWordServiceImpl#listTop10，
 * 无参方法的键是 {@code SimpleKey []}），按热词行的 tenant_id 解析缓存，有事务时推迟到提交后。
 */
@Service
@RequiredArgsConstructor
public class GlobalHotWordServiceImpl extends ServiceImpl<GlobalHotWordMapper, GlobalHotWord>
		implements GlobalHotWordService {

	private final CacheManager cacheManager;

	/** tenant_id 是 INSERT 自动填充的；万一为空按当前租户清——填进去的正是当前租户。 */
	@Override
	public boolean save(GlobalHotWord entity) {
		boolean saved = super.save(entity);
		evictAfterCommit(entity.getTenantId());
		return saved;
	}

	@Override
	public boolean updateById(GlobalHotWord entity) {
		boolean updated = super.updateById(entity);
		GlobalHotWord row = entity.getId() == null ? null : baseMapper.selectById(entity.getId());
		evictAfterCommit(row == null ? entity.getTenantId() : row.getTenantId());
		return updated;
	}

	@Override
	public boolean removeById(Serializable id) {
		// 删之前查：删掉之后就查不到热词行（和它的 tenant_id）了
		GlobalHotWord row = baseMapper.selectById(id);
		boolean removed = super.removeById(id);
		if (row != null) {
			evictAfterCommit(row.getTenantId());
		}
		return removed;
	}

	private void evictAfterCommit(String tenantId) {
		AfterCommitCacheEviction.evictForTenant(cacheManager, tenantId, CacheConstants.MALL_HOTWORD_CACHE, SimpleKey.EMPTY);
	}

	@Override
	public boolean wordExists(String word, Long excludeId) {
		// 实体没有 @TableLogic，软删标记显式过滤（唯一索引也只约束 del_flag='0' 的行，见 V80）
		return lambdaQuery()
				.eq(GlobalHotWord::getDelFlag, "0")
				.eq(GlobalHotWord::getWord, word)
				.ne(excludeId != null, GlobalHotWord::getId, excludeId)
				.exists();
	}
}
