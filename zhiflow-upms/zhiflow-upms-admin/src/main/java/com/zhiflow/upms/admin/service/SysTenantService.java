package com.zhiflow.upms.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.upms.common.entity.SysTenant;

import java.util.List;

/**
 * <p>
 * 租户管理 服务类
 * </p>
 *
 * @author
 */
public interface SysTenantService extends IService<SysTenant> {

	/**
	 * 获取平台系统用户绑定的租户
	 * @return
	 */
	List<SysTenant> listSysTenant(String userId);

	/**
	 * 服务间调用的全量租户清单（{@code type=1}、未软删），带 60s 缓存。
	 * <p>
	 * 单独开一个方法而不是直接给 {@code getListInside} 端点挂 {@code @Cacheable}：
	 * 缓存的是 {@code List<SysTenant>} 而非 {@code R} 包装体，避免把响应包装类塞进 Redis
	 * （序列化面更大、以后改 R 的结构会让存量缓存反序列化失败）。
	 * <p>
	 * 租户增删改会显式 evict，见 {@code SysTenantServiceImpl} 上的 {@code @CacheEvict}。
	 */
	List<SysTenant> listInsideTenants();
}
