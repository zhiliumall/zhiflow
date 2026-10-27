/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.util;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.safety.Safelist;

/**
 * 富文本白名单净化工具（入库根本防线）。
 * <p>
 * 对后台编辑器产出的富文本做白名单清洗：剥离 script/iframe/object/embed/form 等危险标签、
 * 所有 on* 事件属性、javascript: 伪协议链接；同时保留文章正文常见的标签与内联样式
 * （颜色、字号、对齐、表格、待办列表、图片、视频、代码块等），避免误伤合法排版。
 * <p>
 * 这是三层防御中的“服务端入库净化”，是最根本的一层：数据一旦入库即为干净数据，
 * 后台只读态与 C 端渲染都不再执行脚本。前端 DOMPurify、C 端渲染净化为纵深兜底。
 *
 */
public final class RichTextSanitizer {

    private RichTextSanitizer() {
    }

    /** 富文本白名单：在 jsoup relaxed 基础上放开编辑器需要的标签/属性。 */
    private static final Safelist SAFELIST = Safelist.relaxed()
            .addTags("s", "del", "ins", "hr", "video", "source", "input")
            // 文章自定义节点（商品卡/提示框/折叠 FAQ）的容器标签。
            // relaxed 白名单不含 section，不放行则节点入库即被剥成裸文本。
            .addTags("section")
            // jsoup 不支持通配属性名，data-zf-* 必须逐个列出
            .addAttributes("section",
                    "data-zf-node", "data-zf-level", "data-zf-spu-id", "data-zf-slot", "data-zf-open")
            .addAttributes("p", "data-zf-slot")
            .addAttributes("div", "data-zf-slot")
            // 内联样式与 class 是富文本排版（颜色/字号/对齐）的载体。
            // 自定义节点的配色也依赖内联 style —— C 端 mp-html 不接 scoped class。
            .addAttributes(":all", "style", "class")
            .addAttributes("img", "src", "alt", "title", "width", "height", "style")
            .addAttributes("video", "src", "controls", "poster", "width", "height")
            .addAttributes("source", "src", "type")
            // wangEditor 待办列表依赖 <input type="checkbox">
            .addAttributes("input", "type", "checked", "disabled")
            .addAttributes("td", "colspan", "rowspan")
            .addAttributes("th", "colspan", "rowspan")
            .addAttributes("a", "target", "rel")
            // 图片 src 放开协议限制：保留相对路径 / data: 图片（img src=javascript: 不会执行，
            // 真正危险的 onerror/onload 事件属性已被白名单剥离）。a[href] 仍保留协议校验以拦 javascript:
            .removeProtocols("img", "src", "http", "https")
            // 不把相对链接改写成绝对链接，保持原文内容
            .preserveRelativeLinks(true);

    /** 不美化输出，避免 jsoup 插入多余空白影响渲染。 */
    private static final Document.OutputSettings OUTPUT_SETTINGS =
            new Document.OutputSettings().prettyPrint(false);

    /**
     * 白名单净化富文本 HTML。空/空白输入原样返回。
     *
     * @param html 原始富文本
     * @return 净化后的安全 HTML
     */
    public static String sanitize(String html) {
        if (html == null || html.isEmpty()) {
            return html;
        }
        return Jsoup.clean(html, "", SAFELIST, OUTPUT_SETTINGS);
    }
}
