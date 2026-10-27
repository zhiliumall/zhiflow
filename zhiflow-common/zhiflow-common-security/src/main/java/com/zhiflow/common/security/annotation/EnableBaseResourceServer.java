package com.zhiflow.common.security.annotation;

import com.zhiflow.common.security.component.AuthorizationSnapshotConfiguration;
import com.zhiflow.common.security.component.BaseResourceServerAutoConfiguration;
import com.zhiflow.common.security.component.BaseResourceServerConfiguration;
import org.springframework.context.annotation.Import;

import java.lang.annotation.*;

/**
 * @author 辰
 * @date 2023年10月26日17:30:06
 * <p>
 * 资源服务注解
 * <p>{@link AuthorizationSnapshotConfiguration}（压测优化 A2 的内省缓存与失效订阅）只挂在这里：
 * auth 也 import {@code BaseResourceServerAutoConfiguration}，但它不用内省器，不该多一条订阅。
 */
@Documented
@Inherited
@Target({ ElementType.TYPE })
@Retention(RetentionPolicy.RUNTIME)
@Import({ BaseResourceServerAutoConfiguration.class, BaseResourceServerConfiguration.class,
		AuthorizationSnapshotConfiguration.class })
public @interface EnableBaseResourceServer {

}
