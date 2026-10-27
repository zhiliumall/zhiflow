package com.zhiflow.common.core.util;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.zhiflow.common.core.constant.CommonConstants;
import lombok.*;
import lombok.experimental.Accessors;
import io.swagger.v3.oas.annotations.media.Schema;

import java.io.Serial;
import java.io.Serializable;

/**
 * 响应信息主体
 * 参照mybatis-plus R类
 *
 * <h2>⚠️ 类上的 {@code @JsonIgnoreProperties(ignoreUnknown = true)} 不能删</h2>
 *
 * <p>删了会让<b>所有「值是 R」的 Redis 缓存命中率归零</b>。
 *
 * <p><b>由来（2026-08-20 查实）</b>：本类只有 4 个字段（{@code code}/{@code msg}/
 * {@code errCode}/{@code data}），但 {@link #isOk()} 会被 Jackson 当成派生 getter
 * ⇒ 序列化出第 5 个属性 {@code ok}。而
 * {@code RedisTemplateConfig#createValkeyOptimizedJsonSerializer} 是<b>手搓的
 * {@code new ObjectMapper()}</b>（Spring Boot 自动配置的那个会关掉
 * {@code FAIL_ON_UNKNOWN_PROPERTIES}，手搓的没关），且设了
 * {@code setVisibility(ALL, ANY)} ⇒ <b>写进去带 {@code ok}，读回来不认 {@code ok}</b>。
 *
 * <p>症状：upms 全天持续打
 * {@code Cache[public:dict_cache] key[…] 反序列化失败，删除脏 key 后回源}，
 * 近 24h ≥25 次（{@code zh-hans}/{@code en}/{@code true_false}/
 * {@code authorized_grant_types} 等 key）。{@code DefensiveRedisCache} 会删脏 key 回源，
 * 所以<b>功能一直是对的、只是缓存恒为 0 命中</b>——这类「不报错只变慢」的缺陷
 * 不翻日志发现不了。
 *
 * <p><b>为什么不是给 {@link #isOk()} 加 {@code @JsonIgnore}</b>：那会从<b>全部 9 个服务的
 * 每一个响应体</b>里删掉 {@code ok} 字段。五个前端仓是否在读 {@code .ok} 未逐仓核实，
 * 而静态 grep 判不出完整的字段消费闭包（配置文件里的绑定会被漏掉）。
 * 这里选零输出变更的那条路：{@code ok} 照常写出去，只是读回时忽略。
 *
 * @param <T>
 * @author
 */
@ToString
@NoArgsConstructor
@AllArgsConstructor
@Accessors(chain = true)
@Schema(description = "响应信息主体")
@JsonIgnoreProperties(ignoreUnknown = true)
public class R<T> implements Serializable {
    @Serial
    private static final long serialVersionUID = 1L;

	@Getter
	@Setter
	@Schema(description = "返回标记：成功=0，失败=1")
	private int code;

	@Getter
	@Setter
	@Schema(description = "返回信息")
	private String msg;

	/**
	 * 机器可读的错误码（可空），前端据此分支处理（如登录返回的 credentials_expired = 须先改密）；
	 * 缺省 null 时前端看 msg。
	 */
	@Getter
	@Setter
	@Schema(description = "i18n 错误码（可空，前端按码取多语言；缺则用 msg）")
	private String errCode;


	@Getter
	@Setter
	@Schema(description = "数据")
	private T data;

	public Boolean isOk() {
		if(code!= 0){
			return false;
		}else{
			return true;
		}
	}

	public static <T> R<T> ok() {
		return restResult(null, CommonConstants.SUCCESS, null);
	}

	public static <T> R<T> ok(T data) {
		return restResult(data, CommonConstants.SUCCESS, null);
	}

	public static <T> R<T> ok(T data, String msg) {
		return restResult(data, CommonConstants.SUCCESS, msg);
	}

	public static <T> R<T> failed() {
		return restResult(null, CommonConstants.FAIL, null);
	}

	public static <T> R<T> failed(String msg) {
		return restResult(null, CommonConstants.FAIL, msg);
	}

	public static <T> R<T> failed(T data) {
		return restResult(data, CommonConstants.FAIL, null);
	}

	public static <T> R<T> failed(T data, String msg) {
		return restResult(data, CommonConstants.FAIL, msg);
	}

	public static <T> R<T> failed(T data, int code, String msg) {
		return restResult(data, code, msg);
	}

	public static <T> R<T> failed(int code, String msg) {
		return restResult(null, code, msg);
	}

	public static <T> R<T> restResult(T data, int code, String msg) {
		R<T> apiResult = new R<>();
		apiResult.setCode(code);
		apiResult.setData(data);
		apiResult.setMsg(msg);
		return apiResult;
	}
}