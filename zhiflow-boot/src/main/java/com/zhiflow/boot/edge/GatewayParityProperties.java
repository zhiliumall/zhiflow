package com.zhiflow.boot.edge;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 网关侧 {@code GatewayConfigProperties} 的逐字对应（prefix 同为 {@code gateway}）。
 *
 * <p>配置键刻意与 {@code zhiflow-gateway-dev.yml} 同名，交付时把网关模板里的这两段
 * 原样搬进 boot 的 data-id 即可，不引入第二套键名：
 * <pre>
 * gateway:
 *   ignore-clients:      # 不校验验证码的客户端（clientId）
 *     - xxx
 * </pre>
 *
 */
@Data
@ConfigurationProperties("gateway")
public class GatewayParityProperties {

	/** 不需要校验验证码的客户端（clientId 取自 Basic 认证头）。 */
	private List<String> ignoreClients = new ArrayList<>();

}
