package com.zhiflow.mall.api.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zhiflow.api.upms.ConfigStorageApi;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.exception.BizBadRequestException;
import com.zhiflow.common.core.util.FileUtils;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.storage.util.UploadFileUtils;
import com.zhiflow.common.portal.annotation.ApiLogin;
import com.zhiflow.upms.common.dto.StorageCredentials;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @date 2019/07/14
 * 文件上传
 */
@Slf4j
@ApiLogin(mustLogin = false)
@RestController
@AllArgsConstructor
@RequestMapping("/file")
@Tag(description = "file", name = "文件上传")
public class UploadFileController {

	private final ConfigStorageApi configStorageApi;

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
	 * {@code fileType} → 允许落盘的扩展名。
	 *
	 * <p>扩展名此前<b>完全由上传者控制</b>：存储拿原始文件名的后缀
	 * 落盘，且文件匿名可读——等于可以在站点域名下托管任意 {@code .html} / {@code .js} 钓鱼页。
	 *
	 * <p>{@code heic}/{@code heif} 必须放行：iOS 相册直传就是这两种格式。
	 * {@code svg} 刻意不放行——它是可执行文档，C 端的「拍照/选相册」链路不会产生 svg，
	 * 放行只增加存储型 XSS 面（存储对 svg 加 attachment 是给后台 {@code /upms/file/upload}
	 * 留的，见 {@code InlineUnsafeTypes}，不是本端点的用途）。
	 *
	 * <p><b>{@code audio} 只放 {@code mp3} 与 {@code m4a}</b>（2026-08-24 开放推广端群聊语音）：
	 * 白名单该写「这条链路真会产生什么」，不是「音频有哪些格式」。本链路只有两个产出口——
	 * 小程序端 {@code RecorderManager} 已配 {@code format:'mp3'}，H5 端在浏览器内用 lamejs
	 * 自编码成 mp3（两端同格式，播放侧零兼容问题，后端因此<b>不需要任何转码</b>）；
	 * {@code m4a} 只留给 iOS 万一降级到原生 {@code MediaRecorder}（{@code audio/mp4}）的兜底。
	 * {@code wav} 不放（无压缩，60 秒约 5MB）、{@code amr} 不放（微信 JSSDK 录音方案已否）。
	 */
	private static final Map<String, Set<String>> ALLOWED_EXTENSIONS = Map.of(
			"image", Set.of("jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif"),
			"video", Set.of("mp4", "mov", "m4v", "webm"),
			"audio", Set.of("mp3", "m4a"));

	/**
	 * 单文件大小上限（字节）。
	 *
	 * <p>为什么在代码里判而不是改配置：{@code spring.servlet.multipart.max-file-size} 是
	 * 全服务共享的全局配置，改它会波及所有服务的上传口，不在这里改。
	 * 这里只收 C 端这一个端点。
	 */
	private static final Map<String, Long> MAX_SIZE_BYTES = Map.of(
			"image", 32L * 1024 * 1024,
			"video", 200L * 1024 * 1024,
			// 语音上限 60 秒（IM 侧 PromoCardContentValidator#validateVoice 卡的就是 1~60），
			// 60 秒 @ 48~64kbps 约 360~480KB——10MB 是两个数量级的余量，同时远低于 image 的 32MB
			"audio", 10L * 1024 * 1024);

	/**
	 * {@code Content-Type} → 扩展名。<b>仅在文件名压根没有扩展名时</b>作为兜底判据。
	 *
	 * <p><b>为什么必须有这条兜底</b>（2026-08-18 出过故障）：uni-app H5 的
	 * {@code uni.uploadFile} 拿到 {@code blob:} 形式的 filePath 时，走 XHR 取回的是
	 * <b>裸 Blob 而非 File</b>（{@code urlToFile} 不经过 {@code blobToFile}），
	 * FormData 给它的默认文件名就是 {@code "blob"}——没有扩展名。
	 * 于是协议签署的签名图这类**由前端 canvas 生成**的上传
	 * 在扩展名白名单上线后全部 400，下单主流程被卡死。
	 *
	 * <p><b>为什么不放大攻击面</b>：{@code Content-Type} 确实由客户端控制，但伪造成
	 * {@code image/png} 得到的扩展名就是 {@code png}，落盘对象也是 {@code .png}——
	 * 与攻击者直接把文件命名为 {@code x.png} 完全等价。映射结果仍要过
	 * {@link #ALLOWED_EXTENSIONS}，白名单边界一点没松。
	 *
	 * <p>刻意<b>不</b>收 {@code application/octet-stream}：那是「不知道是什么」的默认值，
	 * 把它映射到任何扩展名都是猜。真无扩展名又无类型的文件应当被拒。
	 *
	 * <p><b>加 audio 映射不会放宽 image/video</b>：本表只负责「无扩展名时推断后缀」，
	 * 推断结果仍要过 {@link #validateFile} 里的 {@code allowed.contains(extension)}，
	 * 而 {@code allowed} 是按 {@code fileType} 取的<b>那一个</b>集合。
	 * {@code fileType=image} 配一个 {@code audio/mpeg} 的文件 ⇒ 推断出 {@code mp3}
	 * ⇒ 不在 image 集合里 ⇒ 照样 400。
	 *
	 * <p>H5 语音<b>必然</b>走这条兜底：{@code uni.uploadFile} 拿 {@code blob:} URL 时取回的是
	 * 裸 Blob，FormData 默认文件名 {@code "blob"} 没有扩展名（同上文签名图那条故障）。
	 * 所以漏了 {@code audio/mpeg} 这一行的表现是「小程序端正常、H5 端 400」——最容易漏测的组合。
	 */
	private static final Map<String, String> CONTENT_TYPE_EXTENSIONS = Map.ofEntries(
			Map.entry("image/jpeg", "jpg"),
			Map.entry("image/jpg", "jpg"),
			Map.entry("image/png", "png"),
			Map.entry("image/gif", "gif"),
			Map.entry("image/webp", "webp"),
			Map.entry("image/bmp", "bmp"),
			Map.entry("image/heic", "heic"),
			Map.entry("image/heif", "heif"),
			Map.entry("video/mp4", "mp4"),
			Map.entry("video/quicktime", "mov"),
			Map.entry("video/x-m4v", "m4v"),
			Map.entry("video/webm", "webm"),
			// audio/mpeg 是 mp3 的标准 media type（H5 端自编码的 Blob 就打这个）；
			// audio/mp3 非标准但部分 WebView 会发，一并收
			Map.entry("audio/mpeg", "mp3"),
			Map.entry("audio/mp3", "mp3"),
			Map.entry("audio/mp4", "m4a"),
			Map.entry("audio/x-m4a", "m4a"));

	/**
	 * 上传文件
	 * @param mulFile
	 * @param dir 文件存放目录
	 * @param fileType 文件类型 image:图片
	 * @return
	 */
	@Operation(summary = "上传文件")
	@ApiLogin
	@PostMapping("/upload")
	public String uploadFile(@RequestParam("file") MultipartFile mulFile,
							 @RequestParam("dir") String dir,
							 @RequestParam("fileType") String fileType) throws Exception {
		String normalizedType = validateFileType(fileType);
		validateDir(dir);
		// 返回「有效扩展名」而不是只判真假：无扩展名走 Content-Type 兜底的那条路上，
		// 推断出的后缀必须真正用于落盘，否则 OSS key 会没有后缀（见 CONTENT_TYPE_EXTENSIONS）。
		String extension = validateFile(mulFile, normalizedType);

		File file = FileUtils.multipartFileToFile(mulFile, extension);
		Map<Object, Object> responseData = new HashMap<>();
		dir = StrUtil.format("{}/{}",TenantContextHolder.getTenantId(),  dir);
		StorageCredentials storageCredentials = configStorageApi.getSysConfigStorage(SecurityConstants.FROM_IN).getData();
		// 没配过存储 = 用本地存储（开源版默认）
		responseData.put("link", UploadFileUtils.uploadFile(file,dir,storageCredentials));
		return JSONUtil.toJsonStr(responseData);
	}

	/**
	 * 校验 {@code fileType}，返回小写归一后的值。
	 *
	 * <p><b>拒绝一律抛 {@link BizBadRequestException}（HTTP 400）</b>，三个理由缺一不可：
	 * <ul>
	 *   <li>不能用 {@code RuntimeException}：{@code GlobalBizExceptionHandler} 的
	 *       {@code Exception.class} 分支刻意不回传原文（2026-07-31 修信息泄露 P0 时定的），
	 *       文案会被吞成「服务异常（错误号 …）」。</li>
	 *   <li><b>不能用 {@code IllegalArgumentException}</b>：它的 handler 回 <b>HTTP 200</b>，
	 *       而三端的上传封装都按 {@code statusCode === 200} 判成功、直接
	 *       {@code resolve(JSON.parse(data).link)} —— 校验拒绝会变成
	 *       <b>resolve(undefined) 而不是 reject</b>，签名图 / 身份证图 / 头像拿着 undefined 继续往下走
	 *       （h5 {@code api/utils.js}、pc {@code api/modules/common.ts}、
	 *       pro-app {@code distributionUploadFile} 三处同形）。</li>
	 *   <li><b>2026-08-17 起也不能用裸 {@code BizException}</b>：业务异常统一
	 *       归位到了 <b>HTTP 200</b>，落进上一条同一个坑。
	 *       {@code BizBadRequestException} 是它的子类、回 <b>400</b>，三端继续走 reject 分支。</li>
	 * </ul>
	 */
	private static String validateFileType(String fileType) {
		if (StrUtil.isBlank(fileType)) {
			throw new BizBadRequestException("fileType 不能为空");
		}
		String normalized = fileType.trim().toLowerCase(Locale.ROOT);
		if (!ALLOWED_EXTENSIONS.containsKey(normalized)) {
			throw new BizBadRequestException("不支持的文件类型：%s".formatted(fileType));
		}
		return normalized;
	}

	/**
	 * 校验 {@code dir}。
	 *
	 * <p><b>只判定、不改写</b>：合法值原样透传给下游拼 key（含末尾有无 {@code /} 的差异）。
	 * 实际 {@code dir} 取值本来就不一致（{@code headimg} 无尾斜杠、{@code goods/appraises/} 有），
	 * 顺手「规范化」会改变对象命名，属于共用产物上的行为漂移。
	 */
	private static void validateDir(String dir) {
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

	/**
	 * 校验文件本体：非空、大小上限、扩展名在 {@code fileType} 的白名单内。
	 *
	 * <p>扩展名取自 {@code getOriginalFilename()}，与 {@code FileUtils#multipartFileToFile}
	 * → 存储最终落盘用的是同一个来源，所以这里判过之后
	 * 下游不会再拿到别的后缀。
	 */
	private static String validateFile(MultipartFile mulFile, String normalizedType) {
		if (mulFile == null || mulFile.isEmpty()) {
			throw new BizBadRequestException("file 不能为空");
		}
		long maxSize = MAX_SIZE_BYTES.get(normalizedType);
		if (mulFile.getSize() > maxSize) {
			throw new BizBadRequestException("文件超过 %dMB 上限".formatted(maxSize / 1024 / 1024));
		}
		Set<String> allowed = ALLOWED_EXTENSIONS.get(normalizedType);
		String originalFilename = mulFile.getOriginalFilename();
		String extension = extensionOf(originalFilename);
		// 只在「压根没有扩展名」时才看 Content-Type。有扩展名却不在白名单的（evil.html）
		// 一律直接拒——否则伪造一个 image/png 头就能绕过白名单，那才是真放大攻击面。
		if (StrUtil.isBlank(extension)) {
			extension = extensionFromContentType(mulFile.getContentType());
		}
		if (!allowed.contains(extension)) {
			throw new BizBadRequestException("不支持的文件格式：%s".formatted(
					StrUtil.isBlank(extension) ? "(无扩展名)" : extension));
		}
		return extension;
	}

	/** {@code Content-Type} 兜底推断扩展名；无法判定时返回空串（调用方按「不支持」拒）。 */
	private static String extensionFromContentType(String contentType) {
		if (StrUtil.isBlank(contentType)) {
			return "";
		}
		// 形如 "image/png; charset=binary"，取分号前的 media type
		String mediaType = StrUtil.subBefore(contentType, ";", false).trim().toLowerCase(Locale.ROOT);
		return CONTENT_TYPE_EXTENSIONS.getOrDefault(mediaType, "");
	}

	/** 取小写扩展名；无扩展名或以 {@code .} 结尾时返回空串（一律不在白名单内，会被拒） */
	private static String extensionOf(String filename) {
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
