package com.zhiflow.mall.api.mapper;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门禁：MyBatis 注解 SQL 里 {@code <script>} 块必须是<b>良构 XML</b>。
 *
 * <h2>为什么值得一道专门的门禁</h2>
 * <p>2026-09-08 连续两次发版失败，根因是 {@code DistributionOrderMapper} 的 {@code @Select}
 * 里写了 SQL 惯用的 {@code <>}：
 * <pre>WHEN my.commission IS NOT NULL AND my.commission &lt;&gt; 0 THEN ...</pre>
 * 它在 {@code <script>} 块内，MyBatis 会把整段当 XML 片段解析，裸的 {@code <} 被 SAX
 * 当成标签起始：
 * <pre>
 * MybatisMapperAnnotationBuilder.buildSqlSourceFromStrings
 *   → BuilderException: Error creating document instance
 *   → SAXParseException: The content of elements must consist of
 *     well-formed character data or markup
 *   → sqlSessionFactory 建不起来 → ApplicationContext 启动失败
 * </pre>
 *
 * <p>这类问题有三个特点，叠在一起就是「必然复发」：
 * <ol>
 *   <li><b>全量单测绿也发现不了</b>——注解 SQL 只在 Spring 真正装配 MyBatis 时才解析，
 *       纯 Mockito 单测根本不碰这条路径（出事那次本地 1157 个测试全绿）；</li>
 *   <li><b>本地跑不起完整应用</b>，只能靠发版暴露；</li>
 *   <li><b>{@code <>} 是 SQL 里最自然的写法</b>，下一个人还会这么写。</li>
 * </ol>
 * 所以用静态扫描把它挡在提交前。
 *
 * <h2>规则</h2>
 * <p>只对<b>含 {@code <script>} 的</b>注解 SQL 断言：其中不得出现裸的 {@code <} 或 {@code &}。
 * <ul>
 *   <li>{@code <} → 用 {@code &lt;}；{@code <>} → 用 {@code !=}；{@code <=} → 用 {@code &lt;=}</li>
 *   <li>{@code &} → 用 {@code &amp;}</li>
 *   <li>{@code >} <b>不管</b>——它在 XML 字符数据里本来就合法，{@code >=} 可以照常写</li>
 * </ul>
 * 放行：合法的 MyBatis/XML 标签（{@code <if>}、{@code <foreach>}、{@code <where>} …）、
 * 实体转义（{@code &lt;} {@code &gt;} {@code &amp;} 及数字实体）、{@code <![CDATA[...]]>} 与 XML 注释内的原文。
 *
 * <p><b>不误伤普通注解 SQL</b>：没有 {@code <script>} 包裹的注解走的是纯文本路径、不做 XML 解析，
 * {@code create_time <= #{endTime}} 这类写法一直是好的（如 {@code OrderItemMapper}），
 * 不在本门禁范围内。非注解的字符串常量（如
 * {@code PromoLedgerDailySnapshotMapper.BALANCE_MOVING_SOURCE}）同样不会被扫到。
 */
class MapperAnnotationScriptXmlWellFormedTest {

    /** 两个模块的 mapper 目录（工作目录 = mall-api 模块根） */
    private static final List<Path> MAPPER_ROOTS = List.of(
            Paths.get("src/main/java/com/zhiflow/mall/api/mapper"),
            Paths.get("../zhiflow-mall-admin/src/main/java/com/zhiflow/mall/admin/mapper"));

    private static final Pattern ANNOTATION =
            Pattern.compile("@(Select|Update|Insert|Delete)\\s*\\(");
    private static final Pattern STRING_LITERAL =
            Pattern.compile("\"((?:[^\"\\\\]|\\\\.)*)\"");

    /** MyBatis 动态标签 + XML 结构标签，这些是合法的 */
    private static final String ALLOWED_TAGS =
            "script|if|foreach|where|set|trim|choose|when|otherwise|bind|include|selectKey|sql";

    private record ScriptSql(Path file, String annotation, String sql) {
    }

    @Test
    @DisplayName("所有含 <script> 的注解 SQL 都是良构 XML（<> 会让应用启动即崩）")
    void annotationScriptSqlMustBeWellFormedXml() throws IOException {
        List<ScriptSql> scripts = collectScriptSql();

        // 防门禁静默失效：目录挪了 / 正则失配时，扫不到东西也会「通过」
        // 2026-10-20 开源分支删掉大批 mapper 后实测 4 段
        assertTrue(scripts.size() >= 3,
                "只扫到 " + scripts.size() + " 段 <script> 注解 SQL，明显偏少——"
                        + "很可能是 mapper 目录挪了或注解写法变了，门禁已失效，请修本测试而不是忽略它");

        List<String> violations = new ArrayList<>();
        for (ScriptSql script : scripts) {
            String stripped = stripLegalMarkup(script.sql());
            for (char offender : new char[]{'<', '&'}) {
                int at = stripped.indexOf(offender);
                if (at >= 0) {
                    violations.add(String.format("%s 的 @%s：出现裸的 '%c' —— …%s…",
                            script.file(), script.annotation(), offender,
                            stripped.substring(Math.max(0, at - 60), Math.min(stripped.length(), at + 40))
                                    .replaceAll("\\s+", " ")));
                }
            }
        }

        assertEquals(List.of(), violations,
                "\n<script> 块会被 MyBatis 当 XML 解析，裸的 '<' / '&' 会让 sqlSessionFactory "
                        + "建不起来、应用启动即失败（单测全绿也发现不了，只有发版才暴露）。\n"
                        + "改法：'<>' → '!='；'<=' → '&lt;='；'<' → '&lt;'；'&' → '&amp;'。\n"
                        + "（'>' 和 '>=' 不用改，在 XML 字符数据里本来就合法。）\n");
    }

    // ------------------------------------------------------------------ 扫描

    private List<ScriptSql> collectScriptSql() throws IOException {
        List<ScriptSql> result = new ArrayList<>();
        for (Path root : MAPPER_ROOTS) {
            assertTrue(Files.isDirectory(root),
                    "找不到 mapper 目录：" + root.toAbsolutePath() + "（目录挪了？门禁会静默失效）");
            try (Stream<Path> files = Files.walk(root)) {
                for (Path file : files.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String source = Files.readString(file);
                    for (String[] block : annotationBodies(source)) {
                        String sql = joinLiterals(block[1]);
                        if (sql.contains("<script>")) {
                            result.add(new ScriptSql(file, block[0], sql));
                        }
                    }
                }
            }
        }
        return result;
    }

    /**
     * 取出每个 SQL 注解的括号体。手工扫括号而不是用正则：注解体里含带括号的字符串
     * （{@code open='(' close=')'} 这种 foreach 属性到处都是），正则配不平。
     */
    private List<String[]> annotationBodies(String source) {
        List<String[]> bodies = new ArrayList<>();
        Matcher matcher = ANNOTATION.matcher(source);
        while (matcher.find()) {
            int i = matcher.end() - 1;
            int depth = 0;
            while (i < source.length()) {
                char c = source.charAt(i);
                if (c == '"') {
                    i++;
                    while (i < source.length() && source.charAt(i) != '"') {
                        if (source.charAt(i) == '\\') {
                            i++;
                        }
                        i++;
                    }
                } else if (c == '(') {
                    depth++;
                } else if (c == ')') {
                    depth--;
                    if (depth == 0) {
                        bodies.add(new String[]{matcher.group(1), source.substring(matcher.end(), i)});
                        break;
                    }
                }
                i++;
            }
        }
        return bodies;
    }

    /**
     * 把注解体里的字符串字面量拼成一段 SQL。
     * 必须先拼再判——MyBatis 也是把数组元素连起来当一个文档解析的，
     * 逐条判会把跨行拆开的标签（少见但合法）误判成违规。
     */
    private String joinLiterals(String annotationBody) {
        StringBuilder sql = new StringBuilder();
        Matcher matcher = STRING_LITERAL.matcher(annotationBody);
        while (matcher.find()) {
            sql.append(matcher.group(1).replace("\\\"", "\"")).append(' ');
        }
        return sql.toString();
    }

    /** 去掉所有合法标记，剩下的若还有 '<' 或 '&' 就是裸字符 */
    private String stripLegalMarkup(String sql) {
        return sql
                .replaceAll("(?s)<!\\[CDATA\\[.*?]]>", "")          // CDATA 内是原文，随便写
                .replaceAll("(?s)<!--.*?-->", "")                    // XML 注释
                .replaceAll("&(lt|gt|amp|quot|apos);", "")           // 命名实体
                .replaceAll("&#x?[0-9A-Fa-f]+;", "")                 // 数字实体
                .replaceAll("</?(?:" + ALLOWED_TAGS + ")\\b[^>]*?/?>", ""); // 合法标签
    }

    // ------------------------------------------------------------------ 规则自检

    /**
     * 门禁自身的自检：上面那条扫的是真实代码，全绿时无法证明规则真的会抓人。
     * 这里拿「出事那一行」的原文和几个边界样本直接喂给规则。
     */
    @Test
    @DisplayName("规则自检：该抓的抓得住，该放的放得过")
    void ruleCatchesRealOffendersAndSparesLegalMarkup() {
        // 该抓
        assertHasBareChar("<script> SELECT 1 WHERE my.commission <> 0 </script>",
                "2026-09-08 让发版失败的原文，必须被抓住");
        assertHasBareChar("<script> SELECT 1 WHERE t.create_time <= #{end} </script>",
                "裸 <= 同样是非良构");
        assertHasBareChar("<script> SELECT a & b </script>", "裸 & 同样是非良构");

        // 该放
        assertNoBareChar("<script> SELECT 1 WHERE my.commission != 0 </script>", "!= 是修法");
        assertNoBareChar("<script> SELECT 1 WHERE t.create_time &lt;= #{end} </script>", "转义后合法");
        assertNoBareChar("<script> SELECT 1 WHERE a >= 1 AND b > 2 </script>",
                "'>' 在 XML 字符数据里合法，不该误报");
        assertNoBareChar("<script> <where> <if test=\"a != null\"> AND a=#{a} </if> </where>"
                + " <foreach collection=\"x\" item=\"i\" open=\"(\" separator=\",\" close=\")\">#{i}</foreach>"
                + " </script>", "合法 MyBatis 标签不该误报");
        assertNoBareChar("<script> <![CDATA[ WHERE a < b AND c <> d ]]> </script>",
                "CDATA 里是原文，允许裸写");
    }

    private void assertHasBareChar(String sql, String why) {
        String stripped = stripLegalMarkup(sql);
        assertTrue(stripped.indexOf('<') >= 0 || stripped.indexOf('&') >= 0, why);
    }

    private void assertNoBareChar(String sql, String why) {
        String stripped = stripLegalMarkup(sql);
        assertTrue(stripped.indexOf('<') < 0 && stripped.indexOf('&') < 0, why + " —— 误报：" + stripped);
    }
}
