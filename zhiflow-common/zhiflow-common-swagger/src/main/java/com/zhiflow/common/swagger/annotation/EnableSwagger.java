package com.zhiflow.common.swagger.annotation;

import com.zhiflow.common.swagger.config.SwaggerProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import java.lang.annotation.*;

/**
 * 开启 swagger
 */
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Inherited
@EnableConfigurationProperties(SwaggerProperties.class)
public @interface EnableSwagger {

}
