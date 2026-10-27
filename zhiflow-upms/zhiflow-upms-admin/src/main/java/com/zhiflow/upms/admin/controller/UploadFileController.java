package com.zhiflow.upms.admin.controller;

import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.util.FileUtils;
import com.zhiflow.common.core.util.UploadValidator;
import com.zhiflow.common.core.util.WaterMarkUtils;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.storage.util.UploadFileUtils;
import com.zhiflow.upms.admin.service.SysConfigStorageService;
import com.zhiflow.upms.common.dto.StorageCredentials;
import com.zhiflow.upms.common.entity.SysConfigStorage;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.awt.*;
import java.io.File;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import com.zhiflow.common.log.annotation.SysLog;

/**
 * 文件上传。
 *
 * <p><b>为什么两个端点挂 {@code isAuthenticated()} 而不是具体权限码</b>：这是全站唯一的
 * 通用上传通道，多端共用 —— admin 的富文本/素材库/头像/装修图/知识库文档、
 * 其它客户端的图片上传，横跨十几个不相干的业务模块。
 * 任挂一个模块的权限码都会打断其余模块（本仓已因权限码与 sys_menu 对不上踩过 4 次）。
 * {@code isAuthenticated()} 是**如实声明**：本口的门槛就是「登录即可」，与改动前的运行期行为完全一致
 * （资源服务器链本来就要 Bearer token，缺 token 返回 424），只是把隐式约定写成了显式注解，
 * 同时满足 ArchUnit R1b「写端点必须声明鉴权」。仓内同形用法见
 * {@code SysUserSecurityController} 与 {@code SysWxAccountBindingController}。
 *
 * @date 2019/07/14
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/file")
@Tag(description = "file", name = "文件上传")
public class UploadFileController {

	private final SysConfigStorageService sysConfigStorageService;

	/**
	 * {@code fileType} → 单文件大小上限（字节）。
	 *
	 * <p>为什么在代码里判而不是改配置：{@code spring.servlet.multipart.max-file-size} 在
	 * {@code application.yml} 里是 <b>1024MB</b> 且是进程级共享配置，改它会波及全部上传口。
	 * 这里只给本端点加兜底闸，取值刻意宽于各端前端自己的限制
	 * （admin 富文本 100MB、知识库文档 15MB、素材 2MB），只拦「明显不是业务文件」的量级。
	 *
	 * <p>{@code fileType} 的实际取值只有这四种：admin 用 image/video/music/file，
	 * 其它客户端用 image/video/file，H5 只用 image。未知取值走
	 * {@link #DEFAULT_MAX_SIZE_BYTES} 而<b>不拒绝</b>——后端对 fileType 的唯一用途是
	 * 「是不是 image、要不要打水印」，拿它当白名单只会凭空打断没枚举到的调用方。
	 */
	private static final Map<String, Long> MAX_SIZE_BYTES = Map.of(
			"image", 64L * 1024 * 1024,
			"video", 512L * 1024 * 1024,
			"music", 128L * 1024 * 1024,
			"file", 256L * 1024 * 1024);

	private static final long DEFAULT_MAX_SIZE_BYTES = 256L * 1024 * 1024;

	private static long maxSizeOf(String fileType) {
		if (StrUtil.isBlank(fileType)) {
			return DEFAULT_MAX_SIZE_BYTES;
		}
		return MAX_SIZE_BYTES.getOrDefault(fileType.trim().toLowerCase(Locale.ROOT), DEFAULT_MAX_SIZE_BYTES);
	}

	/**
	 * 校验本端点的三件事：文件非空、大小在上限内、扩展名不是可执行文档。
	 * {@code dir} 单独判，因为它决定 OSS key 的落点。
	 */
	private static void validate(MultipartFile mulFile, String dir, String fileType) {
		UploadValidator.requireNonEmpty(mulFile);
		UploadValidator.requireSafeDir(dir);
		UploadValidator.requireMaxSize(mulFile, maxSizeOf(fileType));
		UploadValidator.rejectExecutableExtension(mulFile);
	}

	/**
	 * 上传文件
	 * @param mulFile
	 * @param dir 文件存放目录
	 * @param fileType 文件类型 image:图片
	 * @return
	 */
	@Operation(summary = "上传文件")
	@SysLog("上传文件")
	@PostMapping("/upload")
	@PreAuthorize("isAuthenticated()")
	public String uploadFile(@RequestParam("file") MultipartFile mulFile,
							 @RequestParam("dir") String dir,
							 @RequestParam("fileType") String fileType) throws Exception {
		validate(mulFile, dir, fileType);
		File file = FileUtils.multipartFileToFile(mulFile);
		Map<Object, Object> responseData = new HashMap<>();
		dir = StrUtil.format("{}/{}",TenantContextHolder.getTenantId(),  dir);
		SysConfigStorage sysConfigStorage = sysConfigStorageService.getOne();
		// 没配过存储 = 用本地存储（开源版默认）
		if(sysConfigStorage != null && CommonConstants.FILE_TYPE_IMG.equals(fileType) &&
				StrUtil.isNotBlank(sysConfigStorage.getWaterMarkContent())){//图片添加水印
			//添加水印
			file = WaterMarkUtils.markStr(file, Color.GRAY, sysConfigStorage.getWaterMarkContent());
		}
		// UploadFileUtils 现在收投影 DTO（B5 契约治理）：跨进程调用方拿到的就是它，
		// 这里用同一个 StorageCredentials.from 映射，免得两条路径对「凭据取哪几列」有两份答案。
		responseData.put("link", UploadFileUtils.uploadFile(file, dir, (sysConfigStorage == null ? null : StorageCredentials.from(sysConfigStorage))));
		return JSONUtil.toJsonStr(responseData);
	}

	/**
	 * 上传文件（落 out 目录，按系统租户取存储配置）
	 * @param mulFile
	 * @param dir 文件存放目录
	 * @param fileType 文件类型 image:图片
	 * @return
	 */
	@Operation(summary = "上传文件（out 目录）")
	@SysLog("上传文件（out 目录）")
	@PostMapping("/upload/out")
	@PreAuthorize("isAuthenticated()")
	public String uploadFileOut(@RequestParam("file") MultipartFile mulFile,
							 @RequestParam("dir") String dir,
							 @RequestParam("fileType") String fileType) throws Exception {
		validate(mulFile, dir, fileType);
		// 落 out 目录、按系统租户取存储配置，故此处显式设租户
		TenantContextHolder.setTenantId(CommonConstants.SYSTEM_TENANT_ID);
		File file = FileUtils.multipartFileToFile(mulFile);
		Map<Object, Object> responseData = new HashMap<>();
		dir = StrUtil.format("{}/out/{}", TenantContextHolder.getTenantId(), dir);
		SysConfigStorage sysConfigStorage = sysConfigStorageService.getOne();
		// 没配过存储 = 用本地存储（开源版默认）
		if(sysConfigStorage != null && CommonConstants.FILE_TYPE_IMG.equals(fileType) &&
				StrUtil.isNotBlank(sysConfigStorage.getWaterMarkContent())){//图片添加水印
			//添加水印
			file = WaterMarkUtils.markStr(file, Color.GRAY, sysConfigStorage.getWaterMarkContent());
		}
		// UploadFileUtils 现在收投影 DTO（B5 契约治理）：跨进程调用方拿到的就是它，
		// 这里用同一个 StorageCredentials.from 映射，免得两条路径对「凭据取哪几列」有两份答案。
		responseData.put("link", UploadFileUtils.uploadFile(file, dir, (sysConfigStorage == null ? null : StorageCredentials.from(sysConfigStorage))));
		return JSONUtil.toJsonStr(responseData);
	}
}
