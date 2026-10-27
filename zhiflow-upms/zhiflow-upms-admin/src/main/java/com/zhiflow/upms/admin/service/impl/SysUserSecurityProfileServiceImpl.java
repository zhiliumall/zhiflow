package com.zhiflow.upms.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.upms.admin.mapper.SysUserSecurityProfileMapper;
import com.zhiflow.upms.admin.service.SysUserSecurityProfileService;
import com.zhiflow.upms.common.entity.SysUserSecurityProfile;
import org.springframework.stereotype.Service;

@Service
public class SysUserSecurityProfileServiceImpl
		extends ServiceImpl<SysUserSecurityProfileMapper, SysUserSecurityProfile>
		implements SysUserSecurityProfileService {
}
