package com.zhiflow.upms.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.data.cache.AfterCommitCacheEviction;
import com.zhiflow.upms.admin.mapper.SysRoleMapper;
import com.zhiflow.upms.admin.mapper.SysRoleMenuMapper;
import com.zhiflow.upms.admin.service.SysRoleService;
import com.zhiflow.upms.common.entity.SysRole;
import com.zhiflow.upms.common.entity.SysRoleMenu;
import lombok.AllArgsConstructor;
import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import com.zhiflow.common.core.constant.CacheConstants;
import java.util.List;

/**
 * <p>
 * 服务实现类
 * </p>
 *
 * @author
 */
@Service
@AllArgsConstructor
public class SysRoleServiceImpl extends ServiceImpl<SysRoleMapper, SysRole> implements SysRoleService {
	private SysRoleMenuMapper sysRoleMenuMapper;
	private final CacheManager cacheManager;

	/**
	 * 通过用户ID，查询角色信息
	 *
	 * @param userId
	 * @return
	 */
	@Override
	public List findRoleIdsByUserId(String userId) {
		return baseMapper.listRoleIdsByUserId(userId);
	}

	/**
	 * 通过角色ID，删除角色,并清空角色菜单缓存
	 *
	 * <p>「清空角色菜单缓存」这句注释一直在，代码却只清了令牌缓存（{@code OAUTH_CLIENT_CACHE}，
	 * 即全部 access_token）。每个请求的令牌内省都从用户缓存取 {@code BaseUser}，而用户缓存没清，
	 * 已删角色的权限照旧带着；菜单缓存里这个角色的菜单也还在，最长 2 小时。
	 * 现补上菜单与用户缓存，且在事务提交后再清。令牌缓存的清法保持原样。
	 *
	 * @param id
	 * @return
	 */
	@Override
	@CacheEvict(value = CacheConstants.OAUTH_CLIENT_CACHE, allEntries = true)
	@Transactional(rollbackFor = Exception.class)
	public Boolean removeRoleById(String id) {
		sysRoleMenuMapper.delete(Wrappers
			.<SysRoleMenu>update().lambda()
			.eq(SysRoleMenu::getRoleId, id));
		Boolean removed = this.removeById(id);
		AfterCommitCacheEviction.clear(cacheManager, CacheConstants.MENU_CACHE, CacheConstants.USER_CACHE);
		return removed;
	}
}
