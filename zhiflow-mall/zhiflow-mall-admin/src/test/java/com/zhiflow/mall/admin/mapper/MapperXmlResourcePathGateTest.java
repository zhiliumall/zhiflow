package com.zhiflow.mall.admin.mapper;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门禁：所有 mapper 契约测试引用的 XML 路径都必须真实存在。
 *
 * <h2>为什么需要这条门禁</h2>
 *
 * mapper 契约测试是「读 XML 源文件 + 断言 SQL 片段」的护栏，它们守的都是真问题
 * （标签成员判定不能用子串匹配、{@code distribution_apply} 必须走 LATERAL 否则扇出 500、
 * 首页与大屏的统计口径不得互相污染……）。
 *
 * <p>但它们的资源路径是**手写字符串**，写错了只会在运行时读不到文件。这类失效很隐蔽：
 * 测试报 error 而不是 failure，加上仓库 CI 已停跑，**没人会发现护栏其实一直没在守**。
 * 实测曾有 5 个此类测试（7 个用例）因少写一层 {@code mall-admin/} / {@code mall-api/}
 * 前缀而长期失效——期间它们守护的 SQL 恰好没被改坏，纯属运气。
 *
 * <p>本门禁扫描两个模块下所有 mapper 测试源码里出现的 {@code mapper/**.xml} 字面量，
 * 逐个核对 resources 下是否真有这个文件。路径写错时它会直接指出是哪个测试的哪个路径。
 */
class MapperXmlResourcePathGateTest {

    /** 测试源码里形如 mapper/xxx/YyyMapper.xml 的字面量（允许前导 /）。 */
    private static final Pattern REFERENCE = Pattern.compile("\"(/?mapper/[A-Za-z0-9_./-]+\\.xml)\"");

    private record Module(String testDir, String resourceDir) {}

    /** 本测试在 mall-admin 下，用相对路径跨到 mall-api。 */
    private static final List<Module> MODULES = List.of(
            new Module("src/test/java/com/zhiflow/mall/admin/mapper",
                    "src/main/resources"),
            new Module("../zhiflow-mall-api/src/test/java/com/zhiflow/mall/api/mapper",
                    "../zhiflow-mall-api/src/main/resources"));

    @Test
    void everyMapperXmlReferencedByContractTestsResolves() throws IOException {
        List<String> problems = new ArrayList<>();
        int checked = 0;

        for (Module module : MODULES) {
            Path testDir = Path.of(module.testDir());
            if (!Files.isDirectory(testDir)) {
                problems.add("测试目录不存在（模块结构变了就更新本门禁）：" + testDir);
                continue;
            }
            try (Stream<Path> sources = Files.walk(testDir)) {
                for (Path source : sources.filter(p -> p.toString().endsWith(".java")).toList()) {
                    String code = Files.readString(source, StandardCharsets.UTF_8);
                    Matcher m = REFERENCE.matcher(code);
                    while (m.find()) {
                        checked++;
                        String reference = m.group(1);
                        Path resolved = Path.of(module.resourceDir(),
                                reference.startsWith("/") ? reference.substring(1) : reference);
                        if (!Files.isRegularFile(resolved)) {
                            problems.add(source.getFileName() + " 引用了不存在的 " + reference
                                    + "（期望文件 " + resolved + "）");
                        }
                    }
                }
            }
        }

        assertTrue(checked > 0,
                "没扫到任何 mapper XML 引用，门禁可能已失效（正则或目录结构变了）");
        assertTrue(problems.isEmpty(),
                "mapper 契约测试引用了读不到的 XML —— 这类测试会静默失去守护作用：\n  "
                        + String.join("\n  ", problems));
    }
}
