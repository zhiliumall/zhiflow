package com.zhiflow.mall.admin.util;

import com.zhiflow.common.core.exception.BizBadRequestException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RequiredFieldGuardTest {

    @Test
    @DisplayName("请求体为 null → 400")
    void nullBody() {
        assertThatThrownBy(() -> RequiredFieldGuard.of(null))
                .isInstanceOf(BizBadRequestException.class)
                .hasMessage("请求体不能为空");
    }

    @Test
    @DisplayName("缺值只认 null：空串在 PG 的 NOT NULL 列上合法，照收（不额外收紧）")
    void onlyNullIsMissing() {
        assertThatCode(() -> RequiredFieldGuard.of(new Object()).required("名称", "name", "").check())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RequiredFieldGuard.of(new Object()).required("名称", "name", null).check())
                .isInstanceOf(BizBadRequestException.class)
                .hasMessage("缺少必填项：名称（name）");
    }

    @Test
    @DisplayName("长度按字符（码点）算，与 PG varchar(n) 一致：中文、emoji 各算 1 个")
    void lengthCountsCodePoints() {
        assertThatCode(() -> RequiredFieldGuard.of(new Object()).maxLength("名称", "name", "中文😀", 3).check())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RequiredFieldGuard.of(new Object()).maxLength("名称", "name", "中文😀x", 3).check())
                .isInstanceOf(BizBadRequestException.class)
                .hasMessageContaining("名称（name）最多 3 个字，当前 4 个");
    }

    @Test
    @DisplayName("数组列按入库后的 JSON 长度算（ArrayStringTypeHandler 存 JSON）")
    void jsonArrayLength() {
        // ["ab","cd"] = 11 个字符
        assertThatCode(() -> RequiredFieldGuard.of(new Object()).maxJsonLength("图片", "picUrl", new String[]{"ab", "cd"}, 11).check())
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> RequiredFieldGuard.of(new Object()).maxJsonLength("图片", "picUrl", new String[]{"ab", "cd"}, 10).check())
                .isInstanceOf(BizBadRequestException.class)
                .hasMessageContaining("图片（picUrl）内容过多：入库后 11 个字符，上限 10");
    }

    @Test
    @DisplayName("缺值与超长一次列全")
    void reportsEverythingAtOnce() {
        assertThatThrownBy(() -> RequiredFieldGuard.of(new Object())
                .required("名称", "name", null)
                .required("排序", "sort", null)
                .maxLength("编码", "code", "abc", 2)
                .check())
                .hasMessage("缺少必填项：名称（name）、排序（sort）；超出长度：编码（code）最多 2 个字，当前 3 个");
    }
}
