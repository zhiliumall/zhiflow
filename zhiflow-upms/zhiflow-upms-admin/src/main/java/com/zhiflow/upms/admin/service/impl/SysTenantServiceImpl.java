package com.zhiflow.upms.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.upms.admin.mapper.SysTenantMapper;
import com.zhiflow.upms.admin.service.SysTenantService;
import com.zhiflow.upms.common.entity.SysTenant;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 租户（开源版单租户）：sys_tenant 只有系统租户 -1 与默认租户 1 两行，由基线写入；
 * 不提供建租户、开库、套餐与品牌维护，这里只留读。
 */
@Service
public class SysTenantServiceImpl extends ServiceImpl<SysTenantMapper, SysTenant> implements SysTenantService {

	@Override
	public List<SysTenant> listSysTenant(String userId) {
		return baseMapper.listSysTenant(userId);
	}

	@Override
	public List<SysTenant> listInsideTenants() {
		SysTenant query = new SysTenant();
		query.setType(CommonConstants.TENANT_TYPE_1);
		return this.list(Wrappers.query(query));
	}
}
