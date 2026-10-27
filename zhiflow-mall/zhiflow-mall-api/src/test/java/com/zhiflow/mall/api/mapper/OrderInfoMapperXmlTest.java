package com.zhiflow.mall.api.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * {@code GET /mallapi/orderinfo/list}（弹幕前十条订单）。
 *
 * <p>2026-09-29 压测普查出的 500：{@code missing FROM-clause entry for table "oi"}。
 * MySQL 迁 PG 时 {@code order_info.payment_time} 被写成 {@code oi.payment_time}，
 * 而这句 SQL 里根本没有 {@code oi} 这个别名。
 */
class OrderInfoMapperXmlTest {

    private static final Pattern DECLARED = Pattern.compile("(?i)\\b(?:FROM|JOIN)\\s+(\\w+)(?:\\s+(?:AS\\s+)?(\\w+))?");
    private static final Pattern QUALIFIER = Pattern.compile("\\b([A-Za-z_]\\w*)\\.[A-Za-z_]");

    @Test
    @DisplayName("getList 里用到的表前缀都必须在 FROM/JOIN 里声明过")
    void getListUsesOnlyDeclaredAliases() throws IOException {
        String select = stripLiterals(extractSelect(readMapperXml(), "getList"));

        Set<String> declared = new HashSet<>();
        Matcher d = DECLARED.matcher(select);
        while (d.find()) {
            declared.add(d.group(1));
            if (d.group(2) != null) {
                declared.add(d.group(2));
            }
        }
        Matcher q = QUALIFIER.matcher(select);
        int qualifiers = 0;
        while (q.find()) {
            qualifiers++;
            assertTrue(declared.contains(q.group(1)),
                    "表前缀 " + q.group(1) + " 没有在 FROM/JOIN 里声明——PG 报 missing FROM-clause entry");
        }
        assertTrue(qualifiers > 0, "一个表前缀都没解析到，本护栏的判据已失效");
    }

    @Test
    @DisplayName("getList 只取已付款订单：PG 的 DESC 把 NULL 排最前，不滤掉会被未付款单占满")
    void getListSkipsUnpaidOrders() throws IOException {
        String select = extractSelect(readMapperXml(), "getList");
        assertTrue(select.contains("order_info.payment_time IS NOT NULL"));
        assertFalse(select.contains("oi."));
    }

    private static String stripLiterals(String sql) {
        return sql.replaceAll("'[^']*'", "''");
    }

    private String extractSelect(String xml, String id) {
        int start = xml.indexOf("<select id=\"" + id + "\"");
        if (start < 0) {
            throw new AssertionError("OrderInfoMapper.xml 里找不到 select id=" + id
                    + "——本护栏的判据已失效，先修判据再谈结论");
        }
        int end = xml.indexOf("</select>", start);
        if (end < 0) {
            throw new AssertionError("select id=" + id + " 没有闭合标签");
        }
        // 跳过开标签本身：resultType="java.util.Map" 会被误认成表前缀
        return xml.substring(xml.indexOf('>', start) + 1, end);
    }

    private String readMapperXml() throws IOException {
        try (InputStream inputStream = getClass().getClassLoader()
                .getResourceAsStream("mapper/mall-api/OrderInfoMapper.xml")) {
            if (inputStream == null) {
                throw new IOException("mapper/mall-api/OrderInfoMapper.xml not found"
                        + "（路径少一层模块前缀会让本护栏静默失效）");
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
