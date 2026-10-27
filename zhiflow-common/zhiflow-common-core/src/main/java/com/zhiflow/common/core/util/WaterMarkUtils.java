package com.zhiflow.common.core.util;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.File;
import javax.imageio.ImageIO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class WaterMarkUtils {
	private static final Logger log = LoggerFactory.getLogger(WaterMarkUtils.class);

	/**
	 * 图片添加水印
	 *
	 * @param imgFile
	 *            需要添加水印的图片
	 * @param markContentColor
	 *            水印文字的颜色
	 * @param waterMarkContent
	 *            水印的文字
	 * @return  水印图片
	 */
	public static File markStr(File imgFile, Color markContentColor, String waterMarkContent) {
		// GIF/SVG 跳过：本方法是 ImageIO 重绘后按 png 覆写原文件 ——
		// GIF 会被压成第一帧静态图（动画没了），SVG 更直接：ImageIO.read 返回 null。
		if (!canMark(imgFile)) {
			return imgFile;
		}
		try {
			// 加水印
			BufferedImage bufImg = ImageIO.read(imgFile);
			if (bufImg == null) { // ImageIO 不认识的格式，原样放行，不要拖垮上传
				log.warn("watermark skipped, ImageIO cannot decode: {}", imgFile.getName());
				return imgFile;
			}
			int width = bufImg.getWidth(); //图片宽
			int height = bufImg.getHeight(); //图片高
			Graphics2D g = bufImg.createGraphics();
			g.drawImage(bufImg, 0, 0, width, height, null);
			Font font = new Font("微软雅黑", Font.ITALIC, 45);
			g.setColor(markContentColor); // 根据图片的背景设置水印颜色

			g.setFont(font);
			int x = width -2*getWatermarkLength(waterMarkContent, g); //这是一个计算水印位置的函数，可以根据需求添加
			int y = height - 1*getWatermarkLength(waterMarkContent, g);
			g.drawString(waterMarkContent, x, y);
			g.dispose();

			ImageIO.write(bufImg, "png", imgFile);
			return imgFile;
		} catch (Exception e) {
			log.error("Operation failed", e);
		}
		// 打水印失败返回原文件，不返回 null ——
		// 调用方（UploadFileController）拿到 null 会直接喂给 UploadFileUtils.uploadFile 而 NPE，
		// 整个上传接口 500。水印是辅助功能，失败只该丢水印，不该丢上传。
		return imgFile;
	}

	/**
	 * 该格式能否走 ImageIO 重绘加水印。
	 * gif（多帧动画）/ svg（矢量，ImageIO 无解码器）一律不行。
	 */
	private static boolean canMark(File imgFile) {
		if (imgFile == null) {
			return false;
		}
		String name = imgFile.getName().toLowerCase();
		return !name.endsWith(".gif") && !name.endsWith(".svg");
	}

	/**
	 * 获取水印文字总长度
	 *
	 * @param waterMarkContent
	 *            水印的文字
	 * @param g
	 * @return 水印文字总长度
	 */
	public static int getWatermarkLength(String waterMarkContent, Graphics2D g) {
		return g.getFontMetrics(g.getFont()).charsWidth(waterMarkContent.toCharArray(), 0, waterMarkContent.length());
	}
}
