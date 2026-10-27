package com.zhiflow.common.core.exception;

import java.io.Serial;

/**
 * @author
 */
public class ValidateCodeException extends Exception {
    @Serial
    private static final long serialVersionUID = -7285211528095468156L;

	public ValidateCodeException() {
	}

	public ValidateCodeException(String msg) {
		super(msg);
	}
}
