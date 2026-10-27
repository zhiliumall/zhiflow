package com.zhiflow.common.core.util;

import cn.hutool.core.util.StrUtil;
import com.zhiflow.common.core.exception.BizBadRequestException;
import org.springframework.web.multipart.MultipartFile;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 上传端点的公共校验。
 *
 * <p>为什么单独抽一个类：后台侧的上传口（{@code upms} 的 {@code /file/upload[/out]}、
 * {@code pay} 的 {@code /payconfig/cert/upload}）需要同一套「大小 / 扩展名 / 目录」判定。
 * mall-api 的 {@code UploadFileController} 有一份先落地的同形实现（C 端专用、带 fileType→扩展名映射），
 * 那份**刻意不动**：它的白名单是按 C 端「拍照/选相册」链路裁的，两边合并会互相放宽。
 *
 * <p><b>拒绝一律抛 {@link BizBadRequestException}（HTTP 400）</b>，理由与 mall-api 那份逐字相同，
 * 三条缺一不可：
 * <ul>
 *   <li>不能用 {@code RuntimeException}：{@code GlobalBizExceptionHandler} 的
 *       {@code Exception.class} 分支不回传原文（2026-07-31 修信息泄露 P0 时定的），
 *       文案会被吞成「服务异常（错误号 …）」，用户看不到「文件太大」这种可自助的提示。</li>
 *   <li>不能用 {@code IllegalArgumentException}：它的 handler 回 <b>HTTP 200</b>，
 *       而各端上传封装普遍按 {@code statusCode === 200} 判成功
 *       （pro-app {@code api/api.js:567}、H5 {@code im-pages/*}、admin 的 el-upload {@code on-success}），
 *       校验拒绝会被当成上传成功、拿着 undefined 的 link 继续往下写表单。</li>
 *   <li><b>2026-08-17 起也不能用裸 {@code BizException}</b>：业务异常统一
 *       归位到了 HTTP 200，落进上一条同一个坑。
 *       {@link BizBadRequestException} 是它的子类、回 400，各端继续走 reject / on-error 分支。</li>
 * </ul>
 */
public final class UploadValidator {

	private UploadValidator() {
	}

	/**
	 * {@code dir} 的单个层级允许的字符。刻意不含 {@code .}——排除 {@code ..} 只挡穿越，
	 * 而 OSS 的 key 是字符串不是路径，{@code a/../b} 会原样存成字面量 key，
	 * 结果是「看起来在别处、实际堆在同一个 bucket」的脏目录。白名单比黑名单干净。
	 */
	private static final Pattern DIR_SEGMENT = Pattern.compile("[A-Za-z0-9_-]{1,64}");

	private static final int DIR_MAX_LENGTH = 128;

	/** 已知最深的合法取值是 {@code mall/distribution/withdraw/}（3 段），留一段余量 */
	private static final int DIR_MAX_DEPTH = 4;

	/**
	 * 服务端可执行文档扩展名黑名单，对所有 {@code fileType} 一律拒收。
	 *
	 * <p>为什么后台通用上传口用<b>黑名单</b>而不是白名单：它是多端共用的唯一上传通道
	 * （admin 的富文本/素材/头像/知识库文档，其它客户端的图片上传），
	 * 其中知识库文档走 {@code fileType=file}，允许上传任意类型的文件，
	 * 扩展名面**无法穷举**。白名单在这里等于随机打断真实业务，与「零业务回归」要求冲突。
	 *
	 * <p>本名单只收「任何一端都不可能有正当用途、且落到任何一台 web 服务器后会被解释执行」的类型。
	 * 浏览器侧的渲染型风险（html / svg / js）不靠这里挡——那类扩展名知识库文档确实要用，
	 * 由存储在返回时加 {@code Content-Disposition: attachment}，见 {@code InlineUnsafeTypes}。
	 */
	private static final Set<String> DENIED_EXTENSIONS = Set.of(
			"jsp", "jspx", "jspf", "jsw", "jsv",
			"php", "php3", "php4", "php5", "phtml", "pht",
			// 刻意不含 cer：它在 IIS 上曾是 ASP handler，但本仓的 .cer 只出现在证书上传口，
			// 而那两个口走的是 requireExtensionIn 白名单、根本不经过本名单。放进来只会让人误读。
			"asp", "aspx", "asa", "asax", "ashx", "asmx",
			"cgi", "pl", "py", "rb",
			"exe", "dll", "msi", "scr", "com", "bat", "cmd", "vbs", "ps1", "hta",
			"jar", "war", "sh");

	/** 文件非空 */
	public static void requireNonEmpty(MultipartFile file) {
		if (file == null || file.isEmpty()) {
			throw new BizBadRequestException("file 不能为空");
		}
	}

	/** 单文件大小上限 */
	public static void requireMaxSize(MultipartFile file, long maxBytes) {
		if (file.getSize() > maxBytes) {
			throw new BizBadRequestException("文件超过 %dMB 上限".formatted(maxBytes / 1024 / 1024));
		}
	}

	/**
	 * 扩展名必须落在白名单内。用于用途单一、扩展名可穷举的端点（证书上传）。
	 * @return 小写归一后的扩展名
	 */
	public static String requireExtensionIn(MultipartFile file, Set<String> allowed) {
		String extension = extensionOf(file.getOriginalFilename());
		if (!allowed.contains(extension)) {
			throw new BizBadRequestException("不支持的文件格式：%s".formatted(
					StrUtil.isBlank(extension) ? "(无扩展名)" : extension));
		}
		return extension;
	}

	/**
	 * 扩展名不得命中可执行文档黑名单。用于扩展名无法穷举的通用上传口，见 {@link #DENIED_EXTENSIONS}。
	 */
	public static void rejectExecutableExtension(MultipartFile file) {
		String extension = extensionOf(file.getOriginalFilename());
		if (DENIED_EXTENSIONS.contains(extension)) {
			throw new BizBadRequestException("不允许上传该类型文件：%s".formatted(extension));
		}
	}

	/**
	 * 校验 {@code dir}。
	 *
	 * <p><b>只判定、不改写</b>：合法值原样透传给下游拼 key（含末尾有无 {@code /} 的差异）。
	 * 实际 {@code dir} 取值本来就不一致（pro-app 的 {@code signature} 无尾斜杠、
	 * admin 的 {@code material/} 有），顺手「规范化」会改变对象命名，属于共用产物上的行为漂移。
	 */
	public static void requireSafeDir(String dir) {
		if (StrUtil.isBlank(dir)) {
			throw new BizBadRequestException("dir 不能为空");
		}
		if (dir.length() > DIR_MAX_LENGTH) {
			throw new BizBadRequestException("dir 过长");
		}
		// 前导 '/' 会让 key 变成 "1//x"，绝对路径语义；'\' 与 NUL 是各存储后端的路径分隔/截断字符
		if (dir.startsWith("/") || dir.indexOf('\\') >= 0 || dir.indexOf('\0') >= 0) {
			throw new BizBadRequestException("dir 非法：%s".formatted(dir));
		}
		String[] segments = dir.split("/", -1);
		int depth = 0;
		for (int i = 0; i < segments.length; i++) {
			String segment = segments[i];
			// 允许唯一一种空段：结尾的 "xxx/"，即 split 后的最后一个空串
			if (segment.isEmpty()) {
				if (i == segments.length - 1) {
					continue;
				}
				throw new BizBadRequestException("dir 非法：%s".formatted(dir));
			}
			if (!DIR_SEGMENT.matcher(segment).matches()) {
				throw new BizBadRequestException("dir 非法：%s".formatted(dir));
			}
			depth++;
		}
		if (depth == 0 || depth > DIR_MAX_DEPTH) {
			throw new BizBadRequestException("dir 非法：%s".formatted(dir));
		}
	}

	/** 取小写扩展名；无扩展名或以 {@code .} 结尾时返回空串（一律不在任何白名单内，会被拒） */
	public static String extensionOf(String filename) {
		if (StrUtil.isBlank(filename)) {
			return "";
		}
		int dot = filename.lastIndexOf('.');
		if (dot < 0 || dot == filename.length() - 1) {
			return "";
		}
		return filename.substring(dot + 1).toLowerCase(Locale.ROOT);
	}
}
