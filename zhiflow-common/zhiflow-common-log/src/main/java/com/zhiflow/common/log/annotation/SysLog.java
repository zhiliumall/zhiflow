package com.zhiflow.common.log.annotation;

import java.lang.annotation.*;

/**
 * @author
 * 操作日志注解
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SysLog {

	/**
	 * 描述。落 {@code sys_log.title}，是静态字符串，用于按操作类型筛选。
	 *
	 * @return {String}
	 */
	String value();

	/**
	 * 操作内容模板（S4，可选）。SpEL 表达式，渲染结果落 {@code sys_log.content}。
	 *
	 * <p>解决的问题：只有 {@code title="删除电子券"} + {@code params={"id":"1899..."}} 时，
	 * 看日志的人得拿 id 去反查是哪张券——而那条记录往往已经被删了。
	 *
	 * <pre>{@code
	 * @SysLog(value = "删除电子券",
	 *         content = "'删除优惠券模板「' + #coupon.name + '」，连带作废已发放 ' + #impact.total + ' 张'")
	 * }</pre>
	 *
	 * <p>可用变量见 {@link com.zhiflow.common.log.util.LogContentRenderer}。
	 * {@code DELETE /{id}} 这类只有 id 的接口，需要业务代码先把对象放进
	 * {@link com.zhiflow.common.log.context.LogRecordContext}。
	 *
	 * <p><b>不填则行为与改造前完全一致</b>——这是个纯增量的可选能力。
	 * 渲染失败只降级记 WARN，不影响业务。
	 *
	 * <p>⚠️ <b>不要往模板里拼手机号、身份证等敏感字段。</b>
	 * {@code params} 的掩码链按字段名工作（结构化 JSON 能定位到 key 再打码），
	 * 而 content 渲染完就是一个自由文本字符串，无法可靠识别哪段子串该打码。
	 * 「谁做了什么」用名称/昵称 + 影响面数字已经足够追责。
	 *
	 * @return SpEL 模板；默认空表示不启用
	 */
	String content() default "";

	/**
	 * Whether this endpoint's bound {@code @RequestBody} may be included in
	 * {@code sys_log.params}. Query/path metadata remains available when false.
	 * Sensitive command endpoints must opt out rather than relying on field-name
	 * masking, because free-text reasons cannot be reliably classified.
	 *
	 * @deprecated Prefer {@link #captureRequestBody()}. Kept so branches that
	 * introduced this protection under the original name remain source-compatible.
	 */
	@Deprecated
	boolean captureBody() default true;

	/**
	 * Compatibility name used by the article-audit branch. A body is captured
	 * only when both flags are true, so setting either name to false is safe.
	 */
	boolean captureRequestBody() default true;
}
