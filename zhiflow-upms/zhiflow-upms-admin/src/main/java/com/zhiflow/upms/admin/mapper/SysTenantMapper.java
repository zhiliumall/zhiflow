package com.zhiflow.upms.admin.mapper;

import com.baomidou.mybatisplus.annotation.InterceptorIgnore;
import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.zhiflow.upms.common.entity.SysTenant;

import java.util.List;

/**
 * <p>
 * 租户管理 Mapper 接口
 * </p>
 *
 * @since 2019-01-20
 */
@InterceptorIgnore(tenantLine="true")
public interface SysTenantMapper extends BaseMapper<SysTenant> {

	/**
	 * 获取平台系统用户绑定的租户
	 * @return
	 */
	List<SysTenant> listSysTenant(String userId);
}
