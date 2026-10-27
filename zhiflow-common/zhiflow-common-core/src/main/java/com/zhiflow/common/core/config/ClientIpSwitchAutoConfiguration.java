package com.zhiflow.common.core.config;

import com.zhiflow.common.core.util.ClientIpResolver;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.env.Environment;

/**
 * 把 {@link ClientIpResolver} 的开关绑定到 Environment（压测后优化 CARD-06）。
 *
 * <p>{@link ClientIpResolver} 是静态工具（{@code IpUtils}、{@code ClientInfoUtils} 等静态调用点也要用），
 * 所以开关不能靠注入；这里启动时把「每次现读 {@value ClientIpResolver#ENABLED_KEY}」的读取器塞进去，
 * 配置刷新后即时生效。{@code @Lazy(false)}：全局懒加载时也要在启动期绑定，否则开关永远读不到、恒为关。
 */
@AutoConfiguration
@Lazy(false)
public class ClientIpSwitchAutoConfiguration implements InitializingBean {

	private final Environment environment;

	public ClientIpSwitchAutoConfiguration(Environment environment) {
		this.environment = environment;
	}

	@Override
	public void afterPropertiesSet() {
		ClientIpResolver.bindSwitch(() -> ClientIpResolver.parseSwitch(environment.getProperty(ClientIpResolver.ENABLED_KEY)));
	}

}
