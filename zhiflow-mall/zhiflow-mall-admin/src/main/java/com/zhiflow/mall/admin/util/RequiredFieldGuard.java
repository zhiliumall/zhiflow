package com.zhiflow.mall.admin.util;

import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.exception.BizBadRequestException;

import java.util.ArrayList;
import java.util.List;

/**
 * 后台增改接口的入参守卫：只拦「数据库本来就会拒绝」的输入——非空列缺值、超出列长——
 * 让它们以 HTTP 400 + 字段名回给前端，而不是撞上 NOT NULL / varchar(n) 约束变成 500「服务异常」。
 *
 * <p><b>判据与库表约束一一对应，不额外收紧</b>：缺值只认 {@code null}（空串在 PG 的 NOT NULL 列上合法，
 * 照收）；长度按 PG 的字符数（码点）算。凡是这里会拒的请求，原先一定是 500，所以正常请求零回归。
 * 2026-09-29 全接口压测 W 轮：后台 12 个接口缺字段 / 超长全部落成 500，就是这条缺口。
 *
 * <p>抛 {@link BizBadRequestException}：全局处理器回 400、{@code R.failed(msg)}、只记 WARN 不进
 * {@code sys_error_log}（参数错误不是系统故障）；后台 http 封装对 400 会弹出 msg 并 reject，
 * 与 {@code @Valid} 失败的 {@code MethodArgumentNotValidException → 400} 同一种体验。
 * 提示里只放 JSON 字段名与中文名，不放类名。
 */
public final class RequiredFieldGuard {

    private final List<String> missing = new ArrayList<>();

    private final List<String> tooLong = new ArrayList<>();

    private RequiredFieldGuard() {
    }

    /**
     * 开始校验一个请求体。请求体本身为 {@code null} 直接拒（HTTP 层的「空 body」由框架先挡成 400，
     * 这里兜的是服务内部直接调用）。
     */
    public static RequiredFieldGuard of(Object body) {
        if (body == null) {
            throw new BizBadRequestException("请求体不能为空");
        }
        return new RequiredFieldGuard();
    }

    /** 非空列：值为 {@code null} 判缺。 */
    public RequiredFieldGuard required(String label, String field, Object value) {
        if (value == null) {
            missing.add(label + "（" + field + "）");
        }
        return this;
    }

    /** varchar(max) 列：超过 max 个字符判超长；{@code null} 不判（缺不缺由 {@link #required} 管）。 */
    public RequiredFieldGuard maxLength(String label, String field, String value, int max) {
        if (value != null) {
            int len = value.codePointCount(0, value.length());
            if (len > max) {
                tooLong.add(label + "（" + field + "）最多 " + max + " 个字，当前 " + len + " 个");
            }
        }
        return this;
    }

    /**
     * 按 {@code ArrayStringTypeHandler} 存成 JSON 数组字符串的 varchar(max) 列：按入库后的 JSON 长度判。
     */
    public RequiredFieldGuard maxJsonLength(String label, String field, String[] value, int max) {
        if (value != null) {
            String json = JSONUtil.toJsonStr(value);
            int len = json.codePointCount(0, json.length());
            if (len > max) {
                tooLong.add(label + "（" + field + "）内容过多：入库后 " + len + " 个字符，上限 " + max);
            }
        }
        return this;
    }

    /** 有违规就抛 400；提示一次列全，免得用户改一个报一个。 */
    public void check() {
        if (missing.isEmpty() && tooLong.isEmpty()) {
            return;
        }
        List<String> parts = new ArrayList<>(2);
        if (!missing.isEmpty()) {
            parts.add("缺少必填项：" + String.join("、", missing));
        }
        if (!tooLong.isEmpty()) {
            parts.add("超出长度：" + String.join("；", tooLong));
        }
        throw new BizBadRequestException(String.join("；", parts));
    }
}
