package com.zhiflow.mall.admin.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 开发环境开关。不配 = 默认开；本机开发连共享库时设为 false，避免本地进程跑定时任务改到别人的数据。
 */
@Data
@ConfigurationProperties(prefix = "mall.dev")
public class MallDevProperties {
    /** 是否运行 @Scheduled 定时任务（见 mall-api 的 SchedulingConfig） */
    private boolean scheduledTasksEnabled = true;
}
