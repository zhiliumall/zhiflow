/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.service.impl;
import cn.hutool.core.collection.CollectionUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.weixin.admin.mapper.WxUserMapper;
import com.zhiflow.weixin.common.entity.WxApp;
import com.zhiflow.weixin.admin.mapper.WxAppMapper;
import com.zhiflow.weixin.admin.service.WxAppService;
import com.zhiflow.weixin.common.entity.WxUser;
import lombok.AllArgsConstructor;
import me.chanjar.weixin.open.bean.result.*;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.cache.annotation.Caching;
import org.springframework.stereotype.Service;

import java.io.Serializable;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

/**
 * 微信应用
 *
 * @date 2019-03-15 10:26:44
 */
@Service
@AllArgsConstructor
public class WxAppServiceImpl extends ServiceImpl<WxAppMapper, WxApp> implements WxAppService {

	private final WxUserMapper wxUserMapper;

	@Override
	@Cacheable(value = CacheConstants.WXAPP_APP_ID_CACHE, key = "#appId", unless = "#result == null")
	public WxApp findByAppId(String appId) {
		WxApp wxApp = null;
		List<WxApp> listWxApp = baseMapper.findByAppId(appId);
		if(CollectionUtil.isNotEmpty(listWxApp)){
			wxApp = listWxApp.getFirst();
		}
		return wxApp;
	}

	@Override
	@Cacheable(value = CacheConstants.WXAPP_APP_ID_CACHE, key = "#id", unless = "#result == null")
	public WxApp getById(Serializable id) {
		return baseMapper.selectById(id);
	}

	@Override
	@Caching(evict={
			@CacheEvict(value = CacheConstants.WXAPP_WEIXIN_SIGN_CACHE, allEntries = true),
			@CacheEvict(value = CacheConstants.WXAPP_APP_ID_CACHE, allEntries = true)
	})
	public boolean updateById(WxApp entity) {
		baseMapper.updateById(entity);
		return Boolean.TRUE;
	}

	/**
	 * S9：删 wx_app 的同时还要删该 appId 下的全部 wx_user，是跨表级联删除。
	 * 原本无事务——删完主表后若清理 wx_user 失败，会留下一批指向已不存在 appId 的微信用户。
	 * rollbackFor 不可省：Spring 默认只回滚 RuntimeException。
	 *
	 * <p>S8：主表删除改走 {@link WxAppMapper#physicalRemoveById}（理由见该方法注释：
	 * 主键是 appId，删后要按同一 appId 重插）。级联删 wx_user 与缓存清理原样保留。
	 */
	@Override
	@Transactional(rollbackFor = Exception.class)
	@Caching(evict={
			@CacheEvict(value = CacheConstants.WXAPP_WEIXIN_SIGN_CACHE, allEntries = true),
			@CacheEvict(value = CacheConstants.WXAPP_APP_ID_CACHE, allEntries = true)
	})
	public boolean removeById(Serializable id) {
		baseMapper.physicalRemoveById(String.valueOf(id));
		//删除该appId下的微信用户
		if(id != null){
			wxUserMapper.delete(Wrappers.<WxUser>lambdaQuery()
					.eq(WxUser::getAppId, id));
		}
		return Boolean.TRUE;
	}

	/**
	 * 裸物理删单条 wx_app，<b>不做</b>级联删 wx_user、<b>不清</b>缓存。
	 *
	 * <p>专供 {@code WxOpenAuthController.jump} 的「删旧行 → 立刻重插同 appId」。
	 * 那里今天写的是 {@code removeById(wxApp)}（传实体），按 Java 重载决议绑定到
	 * {@code IRepository.removeById(T)} 默认实现 = 裸 {@code deleteById(entity)}，
	 * <b>本来就走不到</b>上面那个带级联和缓存清理的 {@code removeById(Serializable)}
	 * ——已由编译产物的字节码坐实（invokeinterface {@code removeById:(Ljava/lang/Object;)Z}）。
	 * 这里如实复刻该行为：S8 只把物理删语义保住，不顺手改变级联范围。
	 * 「重新授权时不清 wx_user、不清缓存」是既有休眠缺陷，要修另开单。
	 */
	@Override
	public boolean physicalRemoveById(String id) {
		return baseMapper.physicalRemoveById(id) > 0;
	}

    // ---------- S8 波次 4（D3）：物理删实体的四个删除入口必须全部覆盖 ----------
    // WxApp 登记在 PhysicalDeleteRegistry（理由见 WxAppMapper#physicalRemoveById）。
    // MyBatis-Plus 没有「按实体关闭 @TableLogic」的开关，任何未覆盖的入口翻转后都会
    // 静默变成软删；对主键即业务键的实体，墓碑还会让「删后重插」直接主键冲突。

    /**
     * <b>有意的守卫，不是未完成的实现。</b>本入口今天零调用点，也没有现成的批量裸删可一行委托。
     * 留空会让它在翻转后静默走软删（WxApp 是物理删实体，那等于悄悄改变语义）；
     * fail-fast 则让将来第一个误用的人当场看到，而不是事后从数据里发现。
     */
    @Override
    public boolean removeByIds(java.util.Collection<?> idList) {
        throw new UnsupportedOperationException(
                "WxApp 为物理删实体（见 PhysicalDeleteRegistry），此入口未接物理删实现："
                        + "请用 physicalRemove* 或 removeById");
    }

    /**
     * <b>有意的守卫，不是未完成的实现。</b>本入口今天零调用点，也没有现成的批量裸删可一行委托。
     * 留空会让它在翻转后静默走软删（WxApp 是物理删实体，那等于悄悄改变语义）；
     * fail-fast 则让将来第一个误用的人当场看到，而不是事后从数据里发现。
     */
    @Override
    public boolean removeBatchByIds(java.util.Collection<?> idList) {
        throw new UnsupportedOperationException(
                "WxApp 为物理删实体（见 PhysicalDeleteRegistry），此入口未接物理删实现："
                        + "请用 physicalRemove* 或 removeById");
    }

    /**
     * <b>有意的守卫，不是未完成的实现。</b>本入口今天零调用点，也没有现成的批量裸删可一行委托。
     * 留空会让它在翻转后静默走软删（WxApp 是物理删实体，那等于悄悄改变语义）；
     * fail-fast 则让将来第一个误用的人当场看到，而不是事后从数据里发现。
     */
    @Override
    public boolean remove(com.baomidou.mybatisplus.core.conditions.Wrapper<WxApp> queryWrapper) {
        throw new UnsupportedOperationException(
                "WxApp 为物理删实体（见 PhysicalDeleteRegistry），此入口未接物理删实现："
                        + "请用 physicalRemove* 或 removeById");
    }
}
