package com.zhiflow.common.data.shardingtable.handler;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.extension.plugins.handler.TableNameHandler;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.shardingtable.config.ShardingTableProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * 分表实现 动态表名处理器
 * 根据当前租户ID自动拼接表名
 */
@Slf4j
public class ShardingTableHandler implements TableNameHandler {

	@Autowired
	private ShardingTableProperties shardingTableProperties;

	@Override
	public String dynamicTableName(String sql, String tableName) {
		String tenantId = TenantContextHolder.getTenantId();
		if(StrUtil.isNotBlank(tenantId)
				&& !StrUtil.equals(tenantId, CommonConstants.SYSTEM_TENANT_ID)
				&& shardingTableProperties.getTables().contains(tableName)){
			//表名后加拼接租户ID
			return StrUtil.format("{}_{}", tableName, tenantId);
		}
		return tableName;
	}
}
