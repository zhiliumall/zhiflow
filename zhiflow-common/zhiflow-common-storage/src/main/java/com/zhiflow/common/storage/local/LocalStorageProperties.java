/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.storage.local;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 本地存储（方案 K-M16：本地 + MinIO）：没有存储配置、或存储类型选「本地」时，上传的文件落在应用服务器的这个目录，
 * 由应用以 {@link #urlPrefix} 对外提供。
 *
 * <pre>
 * zhiflow.storage.local:
 *   dir: /data/zhiflow/uploads   # 多实例部署时必须是共享目录（NFS 等），否则请改用 MinIO
 *   url-prefix: /files           # 返回给前端的地址前缀；前后端不同域时写完整地址，如 https://api.example.com/files
 * </pre>
 */
@Data
@ConfigurationProperties(prefix = "zhiflow.storage.local")
public class LocalStorageProperties {

	/** 上传文件的根目录。相对路径按进程工作目录解析。 */
	private String dir = "./data/uploads";

	/** 文件访问地址前缀；同时是应用对外提供文件的路径。 */
	private String urlPrefix = "/files";
}
