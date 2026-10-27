package com.zhiflow.common.core.util;

import cn.hutool.core.lang.UUID;
import org.springframework.web.multipart.MultipartFile;

import java.io.*;

/**
 * file工具
 */
public class FileUtils {

	/**
	 * 将MultipartFile转为File
	 * @param mulFile
	 * @return
	 */
	public static File multipartFileToFile(MultipartFile mulFile) throws IOException {
		return multipartFileToFile(mulFile, getExtensionName(mulFile.getOriginalFilename()));
	}

	/**
	 * 将 MultipartFile 转为 File，并**指定**落盘扩展名。
	 *
	 * <p>给「原始文件名没有扩展名、扩展名由调用方另行判定」的场景用
	 * （典型：uni-app H5 上传 {@code blob:} 时 FormData 文件名固定是 {@code "blob"}，
	 * 后端按 {@code Content-Type} 兜底推断，见 {@code UploadFileController}）。
	 * 下游存储（本地 / MinIO）从本 File 的名字取扩展名，
	 * 所以扩展名必须在这一步就落对，否则文件会没有后缀。
	 *
	 * <p>无参重载的行为完全不变：它就是把原文件名的后缀传进来。
	 *
	 * @param extension 不带点的扩展名；空则落盘无后缀（保持旧行为）
	 */
	public static File multipartFileToFile(MultipartFile mulFile, String extension) throws IOException {
		InputStream ins = mulFile.getInputStream();
		String fileName = mulFile.getOriginalFilename();
		String prefix = getFileNameNoEx(fileName)+ UUID.fastUUID();
		String suffix = "."+(extension == null ? "" : extension);
		File toFile = File.createTempFile(prefix,suffix);
		OutputStream os = new FileOutputStream(toFile);
		int bytesRead = 0;
		byte[] buffer = new byte[8192];
		while ((bytesRead = ins.read(buffer, 0, 8192)) != -1) {
			os.write(buffer, 0, bytesRead);
		}
		os.close();
		ins.close();
		return toFile;
	}

	/**
	 * 获取文件扩展名
	 *
	 */
	public static String getExtensionName(String filename) {
		if ((filename != null) && (filename.length() > 0)) {
			int dot = filename.lastIndexOf('.');
			if ((dot >-1) && (dot < (filename.length() - 1))) {
				return filename.substring(dot + 1);
			}
		}
		return filename;
	}

	/**
	 * 获取不带扩展名的文件名
	 *
	 */
	public static String getFileNameNoEx(String filename) {
		if ((filename != null) && (filename.length() > 0)) {
			int dot = filename.lastIndexOf('.');
			if ((dot >-1) && (dot < (filename.length()))) {
				return filename.substring(0, dot);
			}
		}
		return filename;
	}
}