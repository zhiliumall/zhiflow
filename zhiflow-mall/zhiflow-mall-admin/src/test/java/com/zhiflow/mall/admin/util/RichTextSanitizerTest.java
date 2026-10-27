package com.zhiflow.mall.admin.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RichTextSanitizer 单元测试
 * <p>
 * 覆盖两组关注点：
 * <ol>
 *   <li><b>自定义节点必须存活</b> —— 商品卡/提示框/折叠 FAQ 依赖 {@code <section>} 与
 *       {@code data-zf-*}，被剥掉则功能整体失效</li>
 *   <li><b>放宽白名单不得削弱安全防线</b> —— script/事件属性/javascript: 伪协议仍须被剥离</li>
 * </ol>
 */
class RichTextSanitizerTest {

    // ==================== 自定义节点存活 ====================

    @Test
    void sanitize_keepsCalloutNode() {
        String html = "<section data-zf-node=\"callout\" data-zf-level=\"danger\" "
                + "style=\"background:#FDF1EF;border-left:3px solid #C0392B;\">"
                + "<p data-zf-slot=\"title\" style=\"color:#C0392B;\">禁忌</p>"
                + "<div data-zf-slot=\"body\"><p>孕妇及哺乳期妇女禁用。</p></div>"
                + "</section>";

        String out = RichTextSanitizer.sanitize(html);

        assertTrue(out.contains("<section"), "section 容器不能被剥");
        assertTrue(out.contains("data-zf-node=\"callout\""), "节点类型标识不能被剥");
        assertTrue(out.contains("data-zf-level=\"danger\""), "语义档位不能被剥");
        assertTrue(out.contains("data-zf-slot=\"title\""), "p 上的插槽标识不能被剥");
        assertTrue(out.contains("data-zf-slot=\"body\""), "div 上的插槽标识不能被剥");
        // 配色只能内联（C 端 mp-html 不接 scoped class），style 丢了等于视觉全失
        assertTrue(out.contains("background:#FDF1EF"), "内联配色不能被剥");
        assertTrue(out.contains("孕妇及哺乳期妇女禁用。"), "正文内容不能丢");
    }

    @Test
    void sanitize_keepsGoodsNode() {
        String html = "<section data-zf-node=\"goods\" data-zf-spu-id=\"1900000000000000002\">"
                + "<p>示例商品 A</p></section>";

        String out = RichTextSanitizer.sanitize(html);

        assertTrue(out.contains("data-zf-node=\"goods\""));
        assertTrue(out.contains("data-zf-spu-id=\"1900000000000000002\""), "商品 ID 是取数依据，不能被剥");
        assertTrue(out.contains("示例商品 A"), "降级文案不能丢");
    }

    @Test
    void sanitize_keepsFaqNode() {
        String html = "<section data-zf-node=\"faq\">"
                + "<section data-zf-slot=\"qa\" data-zf-open=\"1\">"
                + "<p data-zf-slot=\"q\">大概多久能发货？</p>"
                + "<div data-zf-slot=\"a\"><p>请以商品详情页说明或客服指导为准。</p></div>"
                + "</section></section>";

        String out = RichTextSanitizer.sanitize(html);

        assertTrue(out.contains("data-zf-node=\"faq\""));
        assertTrue(out.contains("data-zf-slot=\"qa\""), "嵌套 section 的插槽标识不能被剥");
        assertTrue(out.contains("data-zf-open=\"1\""), "默认展开标记不能被剥");
        assertTrue(out.contains("大概多久能发货？"));
        assertTrue(out.contains("请以商品详情页说明或客服指导为准。"));
    }

    // ==================== 安全防线不得削弱 ====================

    @Test
    void sanitize_stripsScriptTag() {
        String out = RichTextSanitizer.sanitize("<p>正文</p><script>alert(1)</script>");

        assertFalse(out.contains("<script"), "script 标签必须被剥");
        assertFalse(out.contains("alert(1)"), "script 内容必须被剥");
        assertTrue(out.contains("正文"));
    }

    @Test
    void sanitize_stripsEventAttributeOnCustomNode() {
        // 自定义节点放行了 section，但事件属性绝不能跟着混进来
        String html = "<section data-zf-node=\"callout\" onclick=\"alert(1)\" "
                + "onmouseover=\"steal()\"><p>文案</p></section>";

        String out = RichTextSanitizer.sanitize(html);

        assertTrue(out.contains("data-zf-node=\"callout\""), "合法属性应保留");
        assertFalse(out.contains("onclick"), "on* 事件属性必须被剥");
        assertFalse(out.contains("onmouseover"), "on* 事件属性必须被剥");
    }

    @Test
    void sanitize_stripsImgOnerror() {
        String out = RichTextSanitizer.sanitize("<img src=\"x\" onerror=\"alert(1)\">");

        assertFalse(out.contains("onerror"), "img onerror 是典型存储型 XSS 载体");
    }

    @Test
    void sanitize_stripsJavascriptProtocolLink() {
        String out = RichTextSanitizer.sanitize("<a href=\"javascript:alert(1)\">点我</a>");

        assertFalse(out.contains("javascript:"), "a[href] 的 javascript: 伪协议必须被拦");
    }

    @Test
    void sanitize_blocksAllRequiredInjectionVectors() {
        String out = RichTextSanitizer.sanitize(
                "<script>alert(1)</script><iframe src=\"https://evil.example\"></iframe>"
                        + "<img src=\"data:image/png;base64,AAAA\" onerror=\"alert(2)\">"
                        + "<a href=\"javascript:alert(3)\">链接</a>"
                        + "<svg onload=\"alert(4)\"><circle></circle></svg>");

        assertFalse(out.contains("<script"));
        assertFalse(out.contains("<iframe"));
        assertFalse(out.contains("onerror"));
        assertFalse(out.contains("onload"));
        assertFalse(out.contains("javascript:"));
        // jsoup 1.23 对 data: 图片协议执行更严格的净化；输入中的 data:image/png 不应扩展成 SVG 或保留危险协议。
        assertFalse(out.contains("data:image/svg+xml"));
    }

    @Test
    void sanitize_stripsUnknownDataAttributeOnSection() {
        // 只放行契约内的 data-zf-*，其余 data-* 不应被放行（避免白名单无限扩张）
        String out = RichTextSanitizer.sanitize(
                "<section data-zf-node=\"goods\" data-evil=\"x\"><p>a</p></section>");

        assertTrue(out.contains("data-zf-node"));
        assertFalse(out.contains("data-evil"), "契约外的 data-* 不应被放行");
    }

    // ==================== 边界 ====================

    @Test
    void sanitize_nullAndEmptyPassThrough() {
        assertNull(RichTextSanitizer.sanitize(null));
        assertEquals("", RichTextSanitizer.sanitize(""));
    }

    @Test
    void sanitize_keepsExistingRichTextFeatures() {
        // 放宽白名单不得回归破坏既有富文本能力
        String html = "<p style=\"text-align:center\">居中</p>"
                + "<table><tbody><tr><td colspan=\"2\">格</td></tr></tbody></table>"
                + "<input type=\"checkbox\" checked>"
                + "<video src=\"/v.mp4\" controls></video>";

        String out = RichTextSanitizer.sanitize(html);

        assertTrue(out.contains("text-align:center"), "内联样式");
        assertTrue(out.contains("colspan=\"2\""), "表格合并单元格");
        assertTrue(out.contains("type=\"checkbox\""), "wangEditor 待办列表");
        assertTrue(out.contains("<video"), "视频标签");
    }
}
