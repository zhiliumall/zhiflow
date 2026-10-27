package com.zhiflow.mall.common.orderno;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * `public.id_worker_node` 的建表语句有**两份**，本测试防它们漂移。
 *
 * <h2>为什么会有两份（2026-08-16 出过事故）</h2>
 *
 * 原设计只靠 flyway {@code V102__id_worker_node.sql}，结果 mall-admin 起不来：
 * {@code MallTenantMigrator} 挂在 {@code ApplicationReadyEvent} 上，而
 * {@code RegistryWorkerIdAssigner} 在 **Bean 创建期**就要用这张表——
 * mall-admin 自己既是建表者又是使用者，形成「起不来 → 迁移不跑 → 表永远不存在」的死锁。
 * 健康检查 40 次全未就绪、部署流水线自动回滚；事后查 V102 在 flyway 历史里 0 条、表 0 张。
 *
 * <p>修法是让抢占方自建表（自举），V102 保留以保证迁移历史与新租户环境一致。
 * 代价就是 DDL 有两份——**字段集必须一致**，否则一边建出来的表另一边用不了，
 * 而且这种不一致只会在真实启动时暴露。
 */
class WorkerIdRegistryDdlParityTest {

    private static final Path JAVA_SOURCE = Path.of(
            "src/main/java/com/zhiflow/mall/common/orderno/RegistryWorkerIdAssigner.java");

    private static final Path SQL_SCRIPT = Path.of(
            "../../zhiflow-upms/zhiflow-upms-admin/src/main/resources/db/migration/public/V1__baseline.sql");

    /** 抓 CREATE TABLE ... ( ... ) 里的列名（跳过 CONSTRAINT 行）。 */
    private static final Pattern CREATE_BLOCK = Pattern.compile(
            "CREATE TABLE IF NOT EXISTS public\\.id_worker_node\\s*\\((.*?)\\)\\s*(?:;|\"{3}|\\n\\s*\"{3})",
            Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    private static String read(Path p) throws IOException {
        assertTrue(Files.exists(p), "源文件不存在（路径变了就更新本测试）：" + p);
        return Files.readString(p, StandardCharsets.UTF_8);
    }

    @Test
    void javaSelfBootstrapDdlMatchesTheFlywayScript() throws IOException {
        Set<String> fromJava = columnsOf(read(JAVA_SOURCE), "Java 自举 DDL");
        Set<String> fromSql = columnsOf(read(SQL_SCRIPT), "public 基线");

        assertEquals(fromSql, fromJava,
                "两份 DDL 的列集不一致——一边建出来的表另一边用不了，且只会在真实启动时暴露。"
                        + "\n  public 基线: " + fromSql + "\n  Java: " + fromJava);
        assertTrue(fromJava.contains("slot_id"), "至少要有 slot_id 主键列");
    }

    /** 两边都必须幂等：Java 侧每次启动都跑，与 public 基线谁先执行都要成立。 */
    @Test
    void bothCopiesAreIdempotent() throws IOException {
        for (Path p : new Path[] { JAVA_SOURCE, SQL_SCRIPT }) {
            String content = read(p);
            assertTrue(content.contains("CREATE TABLE IF NOT EXISTS public.id_worker_node"),
                    p + " 的建表必须是 IF NOT EXISTS");
            assertTrue(content.contains("ON CONFLICT"),
                    p + " 的槽位预填必须带 ON CONFLICT，否则重复执行会炸");
        }
    }

    private static Set<String> columnsOf(String content, String label) {
        Matcher m = CREATE_BLOCK.matcher(content);
        assertTrue(m.find(), label + " 里没找到 CREATE TABLE IF NOT EXISTS public.id_worker_node 块");

        Set<String> columns = new LinkedHashSet<>();
        for (String rawLine : m.group(1).split("\n")) {
            String line = rawLine.strip();
            if (line.isEmpty() || line.startsWith("--") || line.toUpperCase().startsWith("CONSTRAINT")) {
                continue;
            }
            String name = line.split("\\s+")[0];
            if (!name.isBlank()) {
                columns.add(name);
            }
        }
        return columns;
    }
}
