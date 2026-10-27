package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.mall.api.mapper.GlobalHotWordMapper;
import com.zhiflow.mall.api.service.GlobalHotWordService;
import com.zhiflow.mall.common.entity.GlobalHotWord;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 搜索页热搜词（只读）。
 *
 * <p>租户隔离靠 {@code SchemaInterceptor} 设置的 search_path（{@code global_hot_word} 不在 base.tenant.tables，
 * 不走 tenant_id 列过滤）。实体不继承 {@code BaseEntity}、没有 {@code @TableLogic}，所以软删标记要显式过滤。
 */
@Service
public class GlobalHotWordServiceImpl extends ServiceImpl<GlobalHotWordMapper, GlobalHotWord> implements GlobalHotWordService {

	@Override
	@Cacheable(value = CacheConstants.MALL_HOTWORD_CACHE, condition = CacheConstants.TENANT_PRESENT)
	public List<GlobalHotWord> listTop10() {
		return list(Wrappers.<GlobalHotWord>lambdaQuery()
				.eq(GlobalHotWord::getDelFlag, "0")
				.orderByDesc(GlobalHotWord::getTotalCount)
				.orderByDesc(GlobalHotWord::getCreateTime)
				.last("limit 10"));
	}
}
