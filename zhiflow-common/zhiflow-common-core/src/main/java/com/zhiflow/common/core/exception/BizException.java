package com.zhiflow.common.core.exception;

public class BizException extends RuntimeException{

    /** i18n 错误码（可空）。带码时全局异常处理器会写入 R.errCode，前端按码取多语言文案；msg 仍作中文 fallback。 */
    private final String errCode;

    /** 业务码（可空）。带码时全局异常处理器用它作 R.code，前端可按码区分原因；不带则为通用失败码。 */
    private final Integer code;

    public BizException(String message) {
        super(message);
        this.errCode = null;
        this.code = null;
    }

    public BizException(String errCode, String message) {
        super(message);
        this.errCode = errCode;
        this.code = null;
    }

    public BizException(int code, String message) {
        super(message);
        this.errCode = null;
        this.code = code;
    }

    public Integer getCode() {
        return code;
    }

    public String getErrCode() {
        return errCode;
    }
}