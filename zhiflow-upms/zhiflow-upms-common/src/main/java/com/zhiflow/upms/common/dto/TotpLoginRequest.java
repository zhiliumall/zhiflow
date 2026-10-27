package com.zhiflow.upms.common.dto;

import lombok.Data;

/** Internal request used by the authorization server before issuing a token. */
@Data
public class TotpLoginRequest {
	private String userId;
	private String tenantId;
	private String code;
}
