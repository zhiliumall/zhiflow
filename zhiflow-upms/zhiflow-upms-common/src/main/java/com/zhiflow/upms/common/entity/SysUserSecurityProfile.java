package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * Per-account security state. This table deliberately contains no raw identity
 * document number and no plaintext TOTP secret.
 */
@Data
@TableName("sys_user_security_profile")
@Schema(description = "Current user's security profile")
public class SysUserSecurityProfile {
	@TableId
	private String userId;
	private String tenantId;
	private LocalDateTime phoneVerifiedAt;
	private LocalDateTime emailVerifiedAt;
	private String totpSecretCiphertext;
	private Boolean totpEnabled;
	private String realNameStatus;
	private String realName;
	private String identityLastFour;
	private LocalDateTime createTime;
	private LocalDateTime updateTime;
}
