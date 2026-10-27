package com.zhiflow.common.log.event;

import com.zhiflow.upms.common.dto.SysLogPayload;
import lombok.AllArgsConstructor;
import lombok.Getter;
import org.springframework.context.ApplicationEvent;

/**
 * @author 辰
 * @date 2023年10月26日18:16:15
 * 系统日志事件
 */

public class SysLogEvent extends ApplicationEvent {
	public SysLogEvent(SysLogPayload source) {
		super(source);
	}
}
