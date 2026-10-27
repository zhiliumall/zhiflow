package com.zhiflow.upms.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 首次登录 / 后台重置之后修改密码（未登录，凭原口令）。
 */
@Data
@Schema(description = "修改过期密码")
public class ExpiredPasswordChangeDTO {

	@NotBlank
	@Schema(description = "账号")
	private String username;

	@NotBlank
	@Schema(description = "原口令（首启日志里打印的随机口令，或管理员重置的口令）")
	private String password;

	@NotBlank
	@Size(min = 8, max = 64, message = "新密码长度 8～64 位")
	@Schema(description = "新密码")
	private String newPassword;
}
