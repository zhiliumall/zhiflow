package com.zhiflow.common.data.tenant;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import java.util.ArrayList;
import java.util.List;

/**
 * 多租户配置
 *
 * @author
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "base.tenant")
public class TenantConfigProperties {

	/**
	 * 维护租户列名称
	 */
	private String column = "tenant_id";

	/**
	 * 多租户的数据表集合
	 */
	private List<String> tables = new ArrayList<>();

	/**
	 * 是否启用注解驱动的租户上下文切换
	 * <p>
	 * 开启时，{@link com.zhiflow.common.data.tenant.aspect.TenantAspect} 会处理
	 * {@link com.zhiflow.common.data.tenant.annotation.IgnoreTenant} 和
	 * {@link com.zhiflow.common.data.tenant.annotation.TenantScope} 注解。
	 * <p>
	 * 关闭时，切面会直接执行目标方法而不进行租户上下文切换，便于灰度发布或紧急回退。
	 * <p>
	 * 默认值：true（启用）
	 */
	private boolean annotationDrivenEnabled = true;
}
