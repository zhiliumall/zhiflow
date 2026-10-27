package com.zhiflow.common.data.tenant;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.handler.TenantLineHandler;
import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.NullValue;
import net.sf.jsqlparser.expression.StringValue;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * @author
 * <p>
 * 租户维护处理器
 */
@Slf4j
public class BaseTenantHandler implements TenantLineHandler {
	@Autowired
	private TenantConfigProperties tenantConfigProperties;

	/**
	 * 获取租户值
	 *
	 * @return 租户值
	 */
	@Override
	public Expression getTenantId() {
		String tenantId = TenantContextHolder.getTenantId();
		log.debug("当前租户为 >> {}", tenantId);
		if (StrUtil.isBlank(tenantId)) {
			return new NullValue();
		}
		return new StringValue(tenantId);
	}

	/**
	 * 获取租户字段名
	 *
	 * @return 租户字段名
	 */
	@Override
	public String getTenantIdColumn() {
		return tenantConfigProperties.getColumn();
	}

	/**
	 * 根据表名判断是否进行过滤
	 * 
	 * <p>跳过条件：仅当租户上下文为空（null/blank）时跳过过滤。
	 * 系统租户ID "-1" 仍需应用租户过滤，确保数据隔离。
	 * 如需跨租户查询，请使用 @IgnoreTenant 注解。
	 *
	 * @param tableName 表名
	 * @return 是否进行过滤
	 */
	@Override
	public boolean ignoreTable(String tableName) {
		String tenantId = TenantContextHolder.getTenantId();
		log.debug("判断是否忽略租户过滤，表名：{}，租户ID：{}", tableName, tenantId);
		// 仅当无租户上下文时跳过过滤，系统租户 "-1" 仍需过滤
		if (StrUtil.isBlank(tenantId)) {
			return true;
		}
		return !tenantConfigProperties.getTables().contains(tableName);
	}
}
