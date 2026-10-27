package com.zhiflow.common.core.util;

import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Files;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 水印工具的降级行为回归。
 *
 * 盯死一件事：markStr 永不返回 null。调用方（UploadFileController）拿到 null 会直接
 * 喂给 UploadFileUtils.uploadFile 而 NPE，整个上传接口 500 —— 这是历史缺陷，
 * 只在后台配了 water_mark_content 时才暴露（生产两个租户当前为空，属潜伏态）。
 */
class WaterMarkUtilsTest {

    /**
     * 造一张真 png，供「正常路径」用。
     * 尺寸要够大：markStr 把文字画在 (width-2*文字宽, height-文字宽)，小图算出来是负坐标，
     * 字就落到画布外、字节无变化，断言会假失败。
     */
    private static File pngFile() throws Exception {
        File f = File.createTempFile("wm-ok", ".png");
        ImageIO.write(new BufferedImage(800, 600, BufferedImage.TYPE_INT_RGB), "png", f);
        return f;
    }

    /** 造一个后缀合法但内容不是图片的文件，模拟 ImageIO 解码失败 */
    private static File undecodableFile(String suffix) throws Exception {
        File f = File.createTempFile("wm-bad", suffix);
        Files.write(f.toPath(), "not an image at all".getBytes());
        return f;
    }

    @Test
    void gif_skipped_bytesUntouched() throws Exception {
        File gif = undecodableFile(".gif");
        byte[] before = Files.readAllBytes(gif.toPath());

        File out = WaterMarkUtils.markStr(gif, Color.GRAY, "智流");

        assertSame(gif, out, "GIF 应原样返回，不走 ImageIO 重绘");
        assertArrayEquals(before, Files.readAllBytes(out.toPath()), "GIF 字节不能被改写（否则动画只剩第一帧）");
    }

    @Test
    void svg_skipped_bytesUntouched() throws Exception {
        File svg = File.createTempFile("wm-svg", ".svg");
        Files.write(svg.toPath(), "<svg xmlns=\"http://www.w3.org/2000/svg\"/>".getBytes());
        byte[] before = Files.readAllBytes(svg.toPath());

        File out = WaterMarkUtils.markStr(svg, Color.GRAY, "智流");

        assertSame(svg, out, "SVG 应原样返回（ImageIO 无 svg 解码器，读出来是 null）");
        assertArrayEquals(before, Files.readAllBytes(out.toPath()));
    }

    @Test
    void undecodable_returnsOriginal_notNull() throws Exception {
        File jpg = undecodableFile(".jpg");

        File out = WaterMarkUtils.markStr(jpg, Color.GRAY, "智流");

        assertNotNull(out, "解码失败必须返回原文件；返回 null 会让上传接口 NPE 500");
        assertSame(jpg, out);
    }

    @Test
    void nullInput_returnsNull_withoutThrowing() {
        assertNull(WaterMarkUtils.markStr(null, Color.GRAY, "智流"));
    }

    @Test
    void png_stillWatermarked() throws Exception {
        File png = pngFile();
        long before = png.length();

        File out = WaterMarkUtils.markStr(png, Color.GRAY, "智流");

        assertNotNull(out);
        assertTrue(out.length() > 0);
        assertNotEquals(before, out.length(), "正常 png 仍应被重绘写回（水印生效）");
    }
}
