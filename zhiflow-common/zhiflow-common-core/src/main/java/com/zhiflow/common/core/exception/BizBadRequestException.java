package com.zhiflow.common.core.exception;

/**
 * 客户端入参非法的业务异常 —— 由全局异常处理器返回 <b>HTTP 400</b> + {@code R(code=1, msg)}。
 *
 * <p><b>为什么需要它</b>：{@link BizException} 已归位到 HTTP 200（业务拒绝属于正常应答，
 * 不该计进 5xx）。但有一类拒绝<b>必须回非 200</b>——终端封装是按 HTTP 状态码判成败的：
 *
 * <pre>{@code
 * // H5 前端的 api/utils.js、其它客户端的文件上传封装（如 distributionUploadFile）
 * // 三处同形
 * if (uploadFileRes.statusCode === 200) { resolve(JSON.parse(uploadFileRes.data).link) }
 * else { reject(...) }
 * }</pre>
 *
 * 回 200 会让校验拒绝变成 {@code resolve(undefined)} 而不是 reject，签名图 / 身份证图 / 头像
 * 带着 {@code undefined} 继续往下走。
 *
 * <p><b>用在哪</b>：{@code uni.uploadFile} / {@code downloadFile} 这类「按 statusCode 判成败」的
 * 端点上做入参校验时。普通 JSON 接口继续用 {@link BizException}——四端的通用请求封装
 * 都按响应体 {@code code} 判成败，200 与 500 等价。
 *
 * <p>继承 {@link BizException}：现存的 {@code catch (BizException e)} 站点全部继续命中。
 */
public class BizBadRequestException extends BizException {

	public BizBadRequestException(String message) {
		super(message);
	}

	public BizBadRequestException(String errCode, String message) {
		super(errCode, message);
	}
}
