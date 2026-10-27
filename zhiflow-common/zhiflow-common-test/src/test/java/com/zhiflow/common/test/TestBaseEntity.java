package com.zhiflow.common.test;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/** 对齐业务实体的 BaseEntity 形状：del_flag / create_time 在父类上 */
@Data
public abstract class TestBaseEntity implements Serializable {

	private String delFlag;

	private LocalDateTime createTime;
}
