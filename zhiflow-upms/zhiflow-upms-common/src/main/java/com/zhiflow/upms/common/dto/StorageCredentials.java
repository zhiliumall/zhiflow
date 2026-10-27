package com.zhiflow.upms.common.dto;

import com.zhiflow.upms.common.entity.SysConfigStorage;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;

/**
 * {@code ConfigStorageApi#getSysConfigStorage} 的投影 —— 取代把 {@code SysConfigStorage} 实体当契约。
 *
 * <p>目前唯一的跨进程调用方是 mall-api 的 {@code UploadFileController}，读的是这组 5 个字段，
 * 且经由共用下游 {@code UploadFileUtils.uploadFile}。
 *
 * <p>{@code isSelf} 与 {@code waterMarkContent} 只在 upms-admin <b>自己的</b> 上传端点里用于加水印，
 * 那条路径直连 {@code SysConfigStorageService}、不经过本契约，所以不带。
 *
 * <p>⚠️ 凭据是<b>明文</b>过线的，与今天 REST 一致：{@code /configstorage/inside} 是唯一一个
 * 不走 {@code desensitize()} 的入口，调用方要拿真凭据去传文件。管理端的 {@code get()} 仍然脱敏。
 * 这两个字段上没有 {@code @Sensitive} 注解（脱敏是 controller 手工做的），所以 codec 不会拦。
 */
@Data
public class StorageCredentials implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	/** 存储类型：'0' 本地 '3' MinIO（开源版只有这两种）。 */
	private String storageType;

	private String accessKeyId;

	private String accessKeySecret;

	private String endpoint;

	private String bucket;

	/**
	 * 实体 → 投影。放在这里而不是各 controller 里抄一遍：upms-admin 自己的上传端点
	 * （直连 service，不经契约）也要同一份映射，两处抄容易漂。
	 */
	public static StorageCredentials from(SysConfigStorage src) {
		if (src == null) {
			return null;
		}
		StorageCredentials out = new StorageCredentials();
		out.setStorageType(src.getStorageType());
		out.setAccessKeyId(src.getAccessKeyId());
		out.setAccessKeySecret(src.getAccessKeySecret());
		out.setEndpoint(src.getEndpoint());
		out.setBucket(src.getBucket());
		return out;
	}
}
