package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

@Data
@Schema(description = "修改密码参数")
public class ChangePasswordDTO implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	@Schema(description = "原密码")
	private String oldPassword;

	@Schema(description = "新密码")
	private String newPassword;
}
