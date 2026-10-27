package com.zhiflow.common.core.util;

import com.zhiflow.common.core.exception.BizBadRequestException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.web.multipart.MultipartFile;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * {@link UploadValidator} 的行为锁。
 *
 * <p>重点锁两件事：
 * <ol>
 *   <li><b>真实在用的取值必须全部放行</b> —— 三端已知的 dir 与扩展名逐个跑一遍。
 *       这一组是「零业务回归」的机器判据：任何一条挂了就说明加固打断了真实业务。</li>
 *   <li>拒绝一律是 {@link BizBadRequestException}（HTTP 400）。若哪天有人改回
 *       {@code BizException}，各端会按 statusCode 200 判成上传成功，是静默数据污染。</li>
 * </ol>
 */
class UploadValidatorTest {

	private static MultipartFile file(String filename, int sizeBytes) {
		return new MockMultipartFile("file", filename, null, new byte[sizeBytes]);
	}

	/** 各端实际用过的全部 dir 取值（回归样本），一条都不能被拒 */
	private static final String[] LIVE_DIRS = {
			// admin
			"wx/", "wx/music/", "mall/user/", "user/", "material/", "im/knowledge/",
			"doctor/", "doctor/signature/", "mall/ensure/", "mall/refund/", "tenantapply/",
			"mall/marketing/", "mall/live/", "mall/supplier/", "mall/distribution/withdraw/",
			// pro-app（注意：无尾斜杠的形态也在用）
			"signature", "im", "im/group/", "goods/",
			// H5
			"offline-message/",
	};

	@Test
	void liveDirs_areAllAccepted() {
		for (String dir : LIVE_DIRS) {
			assertDoesNotThrow(() -> UploadValidator.requireSafeDir(dir),
					"在用的 dir 被拒了，属于加固误伤：" + dir);
		}
	}

	@Test
	void traversalAndAbsoluteDirs_areRejected() {
		for (String dir : new String[] { "../etc/", "a/../../b/", "/abs/", "a\\b/", "a\0b/", "" }) {
			assertThrows(BizBadRequestException.class, () -> UploadValidator.requireSafeDir(dir),
					"非法 dir 未被拦：" + dir);
		}
	}

	@Test
	void dirDepthAndLength_areBounded() {
		assertThrows(BizBadRequestException.class, () -> UploadValidator.requireSafeDir("a/b/c/d/e/"));
		assertThrows(BizBadRequestException.class, () -> UploadValidator.requireSafeDir("a".repeat(129) + "/"));
	}

	/** 三端真实上传过的扩展名（含 IM 群聊任意附件与知识库文档）不得被黑名单误伤 */
	@Test
	void liveExtensions_arePassedByBlacklist() {
		for (String ext : new String[] { "jpg", "jpeg", "png", "gif", "webp", "bmp", "svg", "heic",
				"mp4", "mov", "webm", "mp3", "m4a", "amr", "wav",
				"pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "txt", "md", "html", "htm",
				"eml", "epub", "zip", "rar" }) {
			assertDoesNotThrow(() -> UploadValidator.rejectExecutableExtension(file("x." + ext, 1)),
					"在用的扩展名被拒了，属于加固误伤：" + ext);
		}
	}

	@Test
	void executableExtensions_areRejected() {
		for (String ext : new String[] { "jsp", "JSPX", "php", "phtml", "aspx", "sh", "exe", "jar", "war", "ps1" }) {
			assertThrows(BizBadRequestException.class,
					() -> UploadValidator.rejectExecutableExtension(file("payload." + ext, 1)),
					"可执行扩展名未被拦：" + ext);
		}
	}

	/** 双扩展名绕过：只看最后一段，x.jpg.jsp 必须被拦 */
	@Test
	void doubleExtension_isJudgedByLastSegment() {
		assertThrows(BizBadRequestException.class,
				() -> UploadValidator.rejectExecutableExtension(file("avatar.jpg.jsp", 1)));
		assertDoesNotThrow(() -> UploadValidator.rejectExecutableExtension(file("report.jsp.pdf", 1)));
	}

	@Test
	void certWhitelist_acceptsOnlyListedExtensions() {
		Set<String> allowed = Set.of("pem", "key", "crt", "cer");
		assertEquals("pem", UploadValidator.requireExtensionIn(file("server.PEM", 1), allowed));
		assertThrows(BizBadRequestException.class,
				() -> UploadValidator.requireExtensionIn(file("server.txt", 1), allowed));
		assertThrows(BizBadRequestException.class,
				() -> UploadValidator.requireExtensionIn(file("noext", 1), allowed));
	}

	@Test
	void emptyFileAndOversize_areRejected() {
		assertThrows(BizBadRequestException.class, () -> UploadValidator.requireNonEmpty(file("a.jpg", 0)));
		assertThrows(BizBadRequestException.class, () -> UploadValidator.requireMaxSize(file("a.jpg", 2048), 1024));
		assertDoesNotThrow(() -> UploadValidator.requireMaxSize(file("a.jpg", 1024), 1024));
	}

	@Test
	void extensionOf_handlesEdgeCases() {
		assertEquals("", UploadValidator.extensionOf(null));
		assertEquals("", UploadValidator.extensionOf("noext"));
		assertEquals("", UploadValidator.extensionOf("trailing."));
		assertEquals("gz", UploadValidator.extensionOf("a.tar.gz"));
	}
}
