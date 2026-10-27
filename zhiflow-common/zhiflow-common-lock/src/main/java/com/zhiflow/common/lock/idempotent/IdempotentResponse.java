package com.zhiflow.common.lock.idempotent;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 幂等响应缓存信封。
 *
 * <p>只存「类型全名 + JSON 正文」两段，重放时按类型反序列化回原对象。
 *
 * <p><b>为什么不用 Java 原生序列化</b>：跨服务版本升级后类结构变化会让反序列化直接炸，
 * 而幂等窗口只有 60 秒，JSON 的宽容度更合适（多个字段/少个字段都还能读回来）。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class IdempotentResponse {

	/** 原响应对象的类全名，如 {@code com.zhiflow.common.core.util.R} */
	private String type;

	/** 原响应对象的 JSON */
	private String body;
}
