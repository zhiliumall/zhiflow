package com.zhiflow.boot.assembly;

import com.zhiflow.common.security.annotation.EnableBaseResourceServer;
import com.zhiflow.common.security.component.BaseResourceServerAutoConfiguration;
import com.zhiflow.common.swagger.annotation.EnableSwagger;
import com.zhiflow.common.swagger.config.SwaggerProperties;
import com.zhiflow.mall.admin.config.MallDevProperties;
import com.zhiflow.common.data.shardingtable.config.ShardingTableProperties;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.FilterType;
import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.context.annotation.Import;
import org.springframework.scheduling.annotation.EnableAsync;

/**
 * 单体装配：把 auth / upms / pay / mall-api / mall-admin / weixin 六个业务模块装进一个进程（开源分支唯一的形态，H-11）。
 *
 * <p>各模块不再有自己的 {@code *Application}，原来挂在它们身上的 {@code @EnableXxx}（缓存、异步、OpenAPI、
 * 资源服务器、配置属性）都集中在本类上；调度单独在 mall-api 的 {@code SchedulingConfig}（受开关控制）。模块之间的契约调用全部走进程内的 {@code *LocalApi}。
 *
 * <p><b>bean 名生成器</b>：{@code @ComponentScan} 与 {@code @MapperScan} 都挂
 * {@link FullyQualifiedAnnotationBeanNameGenerator}——不同模块里同简单名的类（如两个 {@code OrderInfoServiceImpl}）
 * 才不会互相覆盖。
 *
 * <h2>⚠️ 跨服务同接口多实现（单体特有的一类新冲突）</h2>
 * <p>P2.1 把 bean 名全限定化，解决的是「同简单名互相覆盖」；但**按类型注入**时，
 * 两个不同名的 bean 只要实现同一个接口就都是候选 ⇒ {@code NoUniqueBeanDefinitionException}。
 * 这一类多进程形态下**不可能出现**（一个进程里只有一份实现），是单体独有的。
 *
 * <p>已知一处：{@code OrderNoFailureSink} 在 mall-api 与 mall-admin 各有一个实现，
 * 类名都叫 {@code ErrorLogOrderNoFailureSink}、**代码逐字相同**（复制粘贴而来 ——
 * mall-common 拿不到 {@code ErrorLogRecorder}，加依赖会把 common-security 拖进它的传递链，
 * 所以那边只留接缝、各服务各写一份实现）。
 *
 * <p>处置是在**装配层**排掉一份，而不是去改那两个业务模块：这样多进程形态代码一行不动
 * （恒等变换的纪律），取舍留在单体特有的文件里。两份实现逐字相同，排哪个都一样。
 * ⚠️ 代价是「将来两份不再等价时，排除会变成静默丢功能」——
 * 所以门禁另有一条断言它们的字节码等价，一旦分叉就红。
 */
@Configuration(proxyBeanMethods = false)
@EnableCaching
@EnableAsync
@EnableSwagger
@EnableBaseResourceServer
@Import(BaseResourceServerAutoConfiguration.class)
@EnableConfigurationProperties({ MallDevProperties.class, ShardingTableProperties.class, SwaggerProperties.class })
@ComponentScan(
		basePackages = { "com.zhiflow.auth", "com.zhiflow.upms.admin",
				"com.zhiflow.pay.admin", "com.zhiflow.mall.api",
				"com.zhiflow.mall.admin", "com.zhiflow.weixin.admin",
				"com.zhiflow.common.data.cache", "com.zhiflow.common.data.resolver" },
		// 单体特有：跨服务的同接口重复实现，见类注释「⚠️ 跨服务同接口多实现」一节
		excludeFilters = @ComponentScan.Filter(type = FilterType.ASSIGNABLE_TYPE,
				classes = { com.zhiflow.mall.admin.config.ErrorLogOrderNoFailureSink.class }),
		nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
@MapperScan(
		basePackages = { "com.zhiflow.upms.admin.mapper", "com.zhiflow.pay.admin.mapper",
				"com.zhiflow.mall.api.mapper", "com.zhiflow.mall.admin.mapper",
				"com.zhiflow.weixin.admin.mapper" },
		nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class AllAssembly {
}
