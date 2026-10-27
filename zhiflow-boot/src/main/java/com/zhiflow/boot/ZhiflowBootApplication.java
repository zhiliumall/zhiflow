package com.zhiflow.boot;

import org.springframework.context.annotation.FullyQualifiedAnnotationBeanNameGenerator;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 唯一可执行 jar：单体 {@code all} 形态装配全部模块，见 {@code AllAssembly}。
 * 原来按 profile 拆的 portal / support / admin-jobs 卫星形态随服务发现基础设施一起删除（开源分支 2026-10-20）。
 *
 * <h2>为什么这件事今天才做得成</h2>
 * <p>两个硬阻塞在阶段 2 已经拆掉了：<b>bean 名</b>全部全限定化（P2.1，否则同名 bean 互相覆盖，
 * 谁生效取决于扫描顺序）、<b>路径</b>全局唯一（P2.3，否则 mall-api 与 mall-admin 大量同路径映射
 * 会直接 {@code Ambiguous mapping}）。第三个是构建：repackage 加了 {@code classifier=exec}
 * 之后各服务的主 jar 才回到普通库 jar，这里才依赖得到。
 *
 * <p><b>bean 名生成器必须与各服务保持一致</b>：它们都用
 * {@link FullyQualifiedAnnotationBeanNameGenerator}，这里不一致的话同一个类在两种装配下
 * 会有两个不同的 bean 名，{@code @Qualifier}/按名注入的地方会静默换人。
 */
@SpringBootApplication(nameGenerator = FullyQualifiedAnnotationBeanNameGenerator.class)
public class ZhiflowBootApplication {

	public static void main(String[] args) {
		SpringApplication.run(ZhiflowBootApplication.class, args);
	}
}
