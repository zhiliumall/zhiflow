package com.zhiflow.pay.common.dto;

import lombok.Data;

import java.util.Map;

@Data
public class AliBaseRequest {
	/**
	 * ISV代商户代用，指定appAuthToken
	 */
	private String appAuthToken;
	private Map<String,Object> params;
}
