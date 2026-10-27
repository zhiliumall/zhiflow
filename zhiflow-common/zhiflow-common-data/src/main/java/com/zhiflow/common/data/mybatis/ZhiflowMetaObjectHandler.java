package com.zhiflow.common.data.mybatis;

import com.baomidou.mybatisplus.core.handlers.MetaObjectHandler;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.security.util.SecurityUtils;
import com.zhiflow.common.security.entity.BaseUser;
import lombok.extern.slf4j.Slf4j;
import org.apache.ibatis.reflection.MetaObject;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

/**
 * MyBatis Plus 自动填充配置
 * 用于自动填充创建时间、更新时间、创建人、更新人、租户ID等字段
 *
 * @date 2025-01-28
 */
@Slf4j
@Component
public class ZhiflowMetaObjectHandler implements MetaObjectHandler {

    @Override
    public void insertFill(MetaObject metaObject) {
        log.debug("开始插入填充...");
        
        // 填充创建时间
        this.strictInsertFill(metaObject, "createTime", LocalDateTime.class, LocalDateTime.now());
        
        // 填充更新时间
        this.strictInsertFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        
        // 填充创建人和更新人
        BaseUser user = SecurityUtils.getUser();
        if (user != null && user.getUsername() != null) {
            this.strictInsertFill(metaObject, "createBy", String.class, user.getUsername());
            this.strictInsertFill(metaObject, "updateBy", String.class, user.getUsername());
        }
        
        // 填充删除标志，默认为0（未删除）
        this.strictInsertFill(metaObject, "delFlag", String.class, "0");
        
        // 填充租户ID
        String tenantId = TenantContextHolder.getTenantId();
        if (tenantId != null) {
            this.strictInsertFill(metaObject, "tenantId", String.class, tenantId);
        }
    }

    @Override
    public void updateFill(MetaObject metaObject) {
        log.debug("开始更新填充...");
        
        // 填充更新时间
        this.strictUpdateFill(metaObject, "updateTime", LocalDateTime.class, LocalDateTime.now());
        
        // 填充更新人
        BaseUser user = SecurityUtils.getUser();
        if (user != null && user.getUsername() != null) {
            this.strictUpdateFill(metaObject, "updateBy", String.class, user.getUsername());
        }
    }
}
