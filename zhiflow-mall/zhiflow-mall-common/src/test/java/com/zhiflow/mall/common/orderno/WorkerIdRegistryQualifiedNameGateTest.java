package com.zhiflow.mall.common.orderno;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门禁：{@code id_worker_node} 的每一次出现都必须带 {@code public.} 前缀。
 *
 * <h2>为什么要用测试守这件事</h2>
 *
 * 本仓多租户是「单数据源 + SET search_path」，{@code MallTenantMigrator} 的 initSql 是
 * {@code SET search_path TO {schema}, public}——**租户 schema 排在 public 前面**。
 * 于是不带前缀的 {@code id_worker_node}：
 * <ul>
 *   <li>若某个租户 schema 里存在同名表，查询会**静默命中租户那张**，不报错；</li>
 *   <li>启动期无租户上下文时，连接落到 DB 默认 schema（实测 {@code zhiflow_mall}，
 *       不是 public），读写打到另一张表。</li>
 * </ul>
 * 两种情况都会让槽位分配分裂成每租户一套，**唯一性保证归零，而全程没有任何异常**。
 * 这类缺陷跑不出来、看不出来，只能用判据钉死。
 */
class WorkerIdRegistryQualifiedNameGateTest {

    private static final String TABLE = "id_worker_node";

    /** 匹配表名出现处，捕获它前面紧邻的字符，用于判断有没有 `public.` 前缀。 */
    private static final Pattern OCCURRENCE = Pattern.compile("(\\w*\\.)?\\b" + TABLE + "\\b");

    private static String read(Path path) throws IOException {
        assertTrue(Files.exists(path), "源文件不存在（路径变了就更新本测试）：" + path);
        return Files.readString(path, StandardCharsets.UTF_8);
    }

    @Test
    void everyTableReferenceInAssignerIsSchemaQualified() throws IOException {
        Path source = Path.of("src/main/java/com/zhiflow/mall/common/orderno/"
                + "RegistryWorkerIdAssigner.java");
        assertAllQualified(read(source), source.toString());
    }

    @Test
    void everyTableReferenceInMigrationScriptIsSchemaQualified() throws IOException {
        // DDL 在 upms-admin 的 public 基线里（共享 schema 迁移器执行），本测试在 mall-common —— 走相对路径跨模块读
        Path script = Path.of("../../zhiflow-upms/zhiflow-upms-admin/src/main/resources/db/migration/public/"
                + "V1__baseline.sql");
        String sql = read(script);
        assertAllQualified(sql, script.toString());

        assertTrue(sql.contains("CREATE TABLE IF NOT EXISTS"),
                "建表必须幂等：迁移器逐 schema 跑，本脚本会被执行 N 次（N 是动态值）");
        assertTrue(sql.contains("ON CONFLICT"),
                "预填槽位必须幂等，否则第二个 schema 执行时会走迁移器的「已存在即标记」回填路径，"
                        + "那条路径会掩盖真实的建表失败");
    }

    /**
     * 逐处检查，并把「未限定的那一处」原样报出来——只说「有 N 处没限定」等于让人重新找一遍。
     */
    private static void assertAllQualified(String content, String label) {
        Matcher m = OCCURRENCE.matcher(content);
        int total = 0;
        int qualified = 0;
        StringBuilder offenders = new StringBuilder();
        while (m.find()) {
            total++;
            String prefix = m.group(1);
            if ("public.".equals(prefix)) {
                qualified++;
            }
            else {
                // 注释/文字说明里提到裸表名是允许的：只有出现在 SQL 里才危险。
                // 判据取「所在行是否像 SQL」——含 SQL 关键字即视为 SQL 语句。
                String line = lineAt(content, m.start());
                if (looksLikeSql(line)) {
                    offenders.append("\n  ").append(line.strip());
                }
                else {
                    qualified++;
                }
            }
        }

        assertTrue(total > 0, label + " 里没找到任何 " + TABLE + " 引用，本测试可能已失效");
        assertEquals(total, qualified,
                label + " 中存在未带 public. 前缀的 " + TABLE + " SQL 引用，"
                        + "会被租户 schema 静默遮蔽：" + offenders);
    }

    private static String lineAt(String content, int index) {
        int start = content.lastIndexOf('\n', index) + 1;
        int end = content.indexOf('\n', index);
        return content.substring(start, end < 0 ? content.length() : end);
    }

    private static boolean looksLikeSql(String line) {
        String upper = line.toUpperCase();
        return upper.contains("SELECT") || upper.contains("UPDATE ") || upper.contains("INSERT")
                || upper.contains("DELETE") || upper.contains("CREATE ") || upper.contains("FROM ")
                || upper.contains("COMMENT ON") || upper.contains("ON public.");
    }
}
