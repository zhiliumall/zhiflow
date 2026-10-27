package com.zhiflow.mall.api.controller;

import com.zhiflow.api.upms.ConfigStorageApi;
import com.zhiflow.common.core.exception.BizBadRequestException;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.R;
import com.zhiflow.upms.common.dto.StorageCredentials;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * {@code POST /mallapi/file/upload} 的入参校验。
 *
 * <p><b>「通过」怎么断言</b>：校验之后紧接着就是按存储配置上传，这里把 {@code ConfigStorageApi}
 * 打桩成返回一个不支持的存储类型（{@value #UNSUPPORTED}），于是「校验放行」的信号就是上传工具那句
 * 「不支持的存储类型」——它证明代码走过了全部三个校验方法，又不会真去写文件或连 MinIO。
 * 反过来「被拒」的信号是 {@link BizException}。两者都是 RuntimeException 的子类，
 * 所以断言必须看类型/文案而不是只看「抛没抛」。
 *
 * <p>鉴权（方法级 {@code @ApiLogin}）不在这里测：它由 {@code ThirdSessionInterceptor} 在进
 * controller 之前执行，单测直接 new controller 走不到；判据是 ArchUnit 的 R1c 规则
 * 与上线后的匿名 curl（回执里记了命令与预期）。
 */
class UploadFileControllerTest {

	private static final String UNSUPPORTED = "9";

	private ConfigStorageApi configStorageApi;
	private UploadFileController controller;

	@BeforeEach
	void setUp() {
		configStorageApi = mock(ConfigStorageApi.class);
		// 返回不支持的存储类型：让「校验通过」这条路径停在一个确定、无副作用的地方（不写本地文件、不连 MinIO）
		StorageCredentials unsupported = new StorageCredentials();
		unsupported.setStorageType(UNSUPPORTED);
		when(configStorageApi.getSysConfigStorage(anyString())).thenReturn(R.ok(unsupported));
		controller = new UploadFileController(configStorageApi);
	}

	private static MultipartFile file(String originalFilename, int sizeBytes) {
		return new MockMultipartFile("file", originalFilename, null, new byte[sizeBytes]);
	}

	/** 带 Content-Type 的上传件——无扩展名兜底那条路要靠它。 */
	private static MultipartFile typed(String originalFilename, String contentType, int sizeBytes) {
		return new MockMultipartFile("file", originalFilename, contentType, new byte[sizeBytes]);
	}

	private static MultipartFile jpg() {
		return file("photo.jpg", 16);
	}

	/**
	 * 断言被校验拒绝，并回传原文案（不是被吞成「服务异常（错误号 …）」）。
	 *
	 * <p>断言的是<b>具体类型</b> {@code BizBadRequestException} 而不是父类 {@code BizException}：
	 * 后者自 2026-08-17 起回 <b>HTTP 200</b>，
	 * 而三端上传封装按 {@code statusCode === 200} 判成功 —— 一旦有人把这里改回裸
	 * {@code BizException}，校验拒绝会变成 {@code resolve(undefined)}。这条断言就是那道闸。
	 */
	private void assertRejected(MultipartFile f, String dir, String fileType, String expectedMessagePrefix) {
		BizBadRequestException ex = assertThrows(BizBadRequestException.class,
				() -> controller.uploadFile(f, dir, fileType));
		assertEquals(expectedMessagePrefix, ex.getMessage().split("：")[0],
				"拒绝文案变了，前端与排查都按它认路：" + ex.getMessage());
	}

	/** 断言校验放行（走到取存储配置那一步） */
	private void assertAccepted(MultipartFile f, String dir, String fileType) throws Exception {
		RuntimeException ex = assertThrows(RuntimeException.class, () -> controller.uploadFile(f, dir, fileType));
		assertFalse(ex instanceof BizException,
				"本该放行却被校验拒了（dir=%s fileType=%s）：%s".formatted(dir, fileType, ex.getMessage()));
		assertTrue(ex.getMessage().startsWith("不支持的存储类型 " + UNSUPPORTED), ex.getMessage());
	}

	// ---------------------------------------------------------------- dir

	/**
	 * 三种已知畸形值 + 同类变体。
	 * {@code 1/../2} 单独说一句：OSS 的 key 是字符串不是路径，它不会真的穿越，
	 * 但会原样存成字面量 key，产生「看着在别处、实际堆在同一个 bucket」的脏目录。
	 */
	@ParameterizedTest(name = "dir={0} 被拒")
	@ValueSource(strings = {
			"../../etc",
			"/abs/path",
			"1/../2",
			"..",
			"a//b",           // 中间空段
			"a/../",
			"a\\b",           // 反斜杠
			"中文目录",
			"a b",            // 空格
			"a;b",
			"a/b/c/d/e",      // 深度 5 > 上限 4
	})
	void dir_malformed_rejected(String dir) {
		assertRejected(jpg(), dir, "image", "dir 非法");
	}

	@Test
	@DisplayName("dir 为空 / 过长被拒")
	void dir_blankOrTooLong_rejected() {
		assertRejected(jpg(), "  ", "image", "dir 不能为空");
		assertRejected(jpg(), "a".repeat(129), "image", "dir 过长");
	}

	/**
	 * <b>各端零影响的机器化断言</b>：各端实际传的每一个 dir 都必须继续放行。
	 * 清单来自 2026-08-17 对 H5 前端 / PC 商城站 / 其它客户端 的完整 grep
	 * （只含真正打到 /mallapi/file/upload 的；其它客户端的 api.js 走 /upms 不在此列）。
	 * 白名单收紧时若误伤其中任何一个，这条会红。
	 */
	@ParameterizedTest(name = "在用 dir={0} 仍放行")
	@ValueSource(strings = {
			"realInfo", "idcard", "signature", "refund",
			"im/image", "im/video", "nutrition/image",
			"headimg", "headimg/", "userAvatar",
			"goods/appraises/", "consultation",
			"mall/distribution/withdraw/",
	})
	void dir_realWorldValues_accepted(String dir) throws Exception {
		assertAccepted(jpg(), dir, "image");
	}

	// ---------------------------------------------------------------- fileType

	@Test
	@DisplayName("fileType 只认 image / video / audio，且大小写不敏感")
	void fileType_whitelist() throws Exception {
		assertAccepted(jpg(), "headimg", "image");
		assertAccepted(jpg(), "headimg", "IMAGE");
		assertAccepted(file("clip.mp4", 16), "im/video", "video");
		assertAccepted(file("voice.mp3", 16), "im", "audio");
		assertAccepted(file("voice.mp3", 16), "im", "AUDIO");

		assertRejected(jpg(), "headimg", "doc", "不支持的文件类型");
		assertRejected(jpg(), "headimg", "", "fileType 不能为空");
		// 「发文件」那条链路传的就是 file——它至今不在白名单，是已知的独立缺陷，
		// 不要因为「顺手」把它加进来：放行哪些文档扩展名是另一个安全面决策
		assertRejected(file("a.pdf", 16), "im", "file", "不支持的文件类型");
	}

	/**
	 * 2026-08-24 开放推广端群聊语音。{@code audio} 只放 {@code mp3}/{@code m4a}——
	 * 小程序端 {@code RecorderManager} 是 {@code format:'mp3'}，H5 端在浏览器内自编码 mp3，
	 * 两端同格式所以后端不转码；{@code m4a} 只是 iOS 降级到原生 MediaRecorder 的兜底。
	 */
	@Test
	@DisplayName("audio 白名单：放行 mp3/m4a，拒 wav/amr/可执行文档")
	void audio_extension_whitelist() throws Exception {
		assertAccepted(file("voice.mp3", 16), "im", "audio");
		assertAccepted(file("voice.M4A", 16), "im", "audio");

		// 刻意不放行的音频格式（无压缩 / 方案已否），别因为「也是音频」就加进来
		assertRejected(file("voice.wav", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("voice.amr", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("voice.aac", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("voice.webm", 16), "im", "audio", "不支持的文件格式");
		// 安全回归：伪装成语音的可执行文档仍被拒
		assertRejected(file("evil.html", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("evil.js", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("payload.exe", 16), "im", "audio", "不支持的文件格式");
		// 通道互不串：audio 通道不收图片/视频后缀，反之亦然
		assertRejected(file("a.jpg", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("clip.mp4", 16), "im", "audio", "不支持的文件格式");
		assertRejected(file("voice.mp3", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(file("voice.mp3", 16), "im/video", "video", "不支持的文件格式");
	}

	/**
	 * H5 语音<b>必然</b>走 Content-Type 兜底：{@code uni.uploadFile} 拿 {@code blob:} URL
	 * 取回的是裸 Blob，FormData 默认文件名 {@code "blob"} 没有扩展名（与签名图同源）。
	 * 漏了 {@code audio/mpeg} 这一行的表现是「小程序端正常、H5 端 400」——最容易漏测的组合。
	 */
	@Test
	@DisplayName("H5 语音：blob 无扩展名按 audio/mpeg 兜底成 mp3，且不放宽 image/video")
	void audio_fallback_by_content_type() throws Exception {
		assertAccepted(typed("blob", "audio/mpeg", 16), "im", "audio");
		assertAccepted(typed("blob", "audio/mp3", 16), "im", "audio");
		assertAccepted(typed("blob", "audio/mpeg; codecs=mp3", 16), "im", "audio");
		assertAccepted(typed("blob", "audio/mp4", 16), "im", "audio");
		assertAccepted(typed("blob", "audio/x-m4a", 16), "im", "audio");

		// 关键回归：新增的 audio 映射不得让 image / video 通道多收任何东西
		assertRejected(typed("blob", "audio/mpeg", 16), "signature", "image", "不支持的文件格式");
		assertRejected(typed("blob", "audio/mpeg", 16), "im/video", "video", "不支持的文件格式");
		// 反向：audio 通道也不收图片类型
		assertRejected(typed("blob", "image/png", 16), "im", "audio", "不支持的文件格式");
		// 未映射的音频类型不猜
		assertRejected(typed("blob", "audio/webm", 16), "im", "audio", "不支持的文件格式");
		assertRejected(typed("blob", "audio/amr", 16), "im", "audio", "不支持的文件格式");
		assertRejected(typed("blob", null, 16), "im", "audio", "不支持的文件格式");
	}

	// ---------------------------------------------------------------- 扩展名与大小

	/**
	 * 扩展名此前完全由上传者控制，而 OSS 对象是 PublicRead——可在公司域名下托管 .html / .js。
	 * heic/heif 必须放行（iOS 相册直传）；svg 刻意不放行（可执行文档，存储型 XSS 面）。
	 */
	@Test
	@DisplayName("扩展名白名单：放行 jpg/png/heic，拒 html/js/svg/无扩展名")
	void extension_whitelist() throws Exception {
		assertAccepted(file("a.jpg", 16), "headimg", "image");
		assertAccepted(file("a.PNG", 16), "headimg", "image");
		assertAccepted(file("IMG_0001.heic", 16), "headimg", "image");

		assertRejected(file("evil.html", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(file("evil.js", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(file("logo.svg", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(file("noext", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(file("trailing.", 16), "headimg", "image", "不支持的文件格式");
		// 类型与扩展名必须对得上：video 通道不收图片后缀
		assertRejected(file("a.jpg", 16), "im/video", "video", "不支持的文件格式");
	}

	/**
	 * 无扩展名时按 {@code Content-Type} 兜底（2026-08-18 故障回归测试）。
	 *
	 * <p>uni-app H5 的 {@code uni.uploadFile} 拿 {@code blob:} filePath 时，
	 * {@code urlToFile} 走 XHR 返回的是<b>裸 Blob 而非 File</b>，FormData 给的默认文件名
	 * 就是 {@code "blob"}——没有扩展名。于是协议签署的签名图这类
	 * 前端 canvas 生成的上传在扩展名白名单上线后全部 400，下单主流程被卡死。
	 *
	 * <p>兜底不放大攻击面：伪造 {@code image/png} 得到的扩展名就是 {@code png}，
	 * 与直接命名 {@code x.png} 等价；映射结果仍要过白名单。
	 */
	@Test
	@DisplayName("无扩展名 → 按 Content-Type 兜底，且仍受白名单约束")
	void extension_fallback_by_content_type() throws Exception {
		// 故障的原始形态：uni-app H5 传上来的就是这个
		assertAccepted(typed("blob", "image/png", 16), "signature", "image");
		assertAccepted(typed("blob", "image/jpeg", 16), "consult", "image");
		// 带参数的 Content-Type 要能解析出 media type
		assertAccepted(typed("blob", "image/png; charset=binary", 16), "signature", "image");
		assertAccepted(typed("blob", "video/quicktime", 16), "im/video", "video");

		// 兜底不越过白名单：类型对不上通道 / 不可执行文档类型 一律仍拒
		assertRejected(typed("blob", "text/html", 16), "signature", "image", "不支持的文件格式");
		assertRejected(typed("blob", "image/svg+xml", 16), "signature", "image", "不支持的文件格式");
		assertRejected(typed("blob", "image/png", 16), "im/video", "video", "不支持的文件格式");
		// octet-stream 是「不知道是什么」，刻意不映射
		assertRejected(typed("blob", "application/octet-stream", 16), "signature", "image", "不支持的文件格式");
		// 无扩展名且无类型：维持原有拒绝（这条是既有行为，不能被兜底放松）
		assertRejected(typed("blob", null, 16), "signature", "image", "不支持的文件格式");
	}

	/**
	 * 有扩展名但不在白名单的，<b>不</b>因为 Content-Type 好看就放行——
	 * 否则伪造一个 image/png 头就能绕过白名单，那才是真放大攻击面。
	 */
	@Test
	@DisplayName("有扩展名时不看 Content-Type：evil.html 声称 image/png 仍被拒")
	void content_type_never_overrides_bad_extension() throws Exception {
		assertRejected(typed("evil.html", "image/png", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(typed("evil.jsp", "image/jpeg", 16), "headimg", "image", "不支持的文件格式");
		assertRejected(typed("logo.svg", "image/png", 16), "headimg", "image", "不支持的文件格式");
	}

	@Test
	@DisplayName("大小上限：image 32MB / video 200MB / audio 10MB，空文件被拒")
	void size_limits() throws Exception {
		assertAccepted(file("a.jpg", 32 * 1024 * 1024), "headimg", "image");
		assertRejected(file("a.jpg", 32 * 1024 * 1024 + 1), "headimg", "image", "文件超过 32MB 上限");
		assertRejected(file("a.jpg", 0), "headimg", "image", "file 不能为空");

		// 60 秒 @ 48~64kbps 约 360~480KB，10MB 是两个数量级的余量
		assertAccepted(file("voice.mp3", 10 * 1024 * 1024), "im", "audio");
		assertRejected(file("voice.mp3", 10 * 1024 * 1024 + 1), "im", "audio", "文件超过 10MB 上限");
		assertRejected(file("voice.mp3", 0), "im", "audio", "file 不能为空");
	}
}
