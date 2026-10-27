package com.zhiflow.common.sms;

/** 短信发送失败（渠道拒绝或调用异常）。 */
public class SmsSendException extends RuntimeException {

	public SmsSendException(String message) {
		super(message);
	}

	public SmsSendException(String message, Throwable cause) {
		super(message, cause);
	}
}
