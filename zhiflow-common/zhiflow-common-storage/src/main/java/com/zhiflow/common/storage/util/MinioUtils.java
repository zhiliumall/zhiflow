package com.zhiflow.common.storage.util;

import com.zhiflow.common.storage.entity.StorageConfig;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.errors.*;
import lombok.AllArgsConstructor;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.URLConnection;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.UUID;

/**
 * MinIO 上传
 */
@AllArgsConstructor
public class MinioUtils {
	private final StorageConfig storageConfig;

	/**
	 * 上传文件
	 * @param file
	 * @param dir 用户上传文件时指定的文件夹。
	 */
	public String uploadFile(File file,String dir) throws IOException, InvalidKeyException, NoSuchAlgorithmException, InsufficientDataException, InternalException, ErrorResponseException, XmlParserException, ServerException, InvalidResponseException {
		MinioClient minioClient = MinioClient.builder().endpoint(storageConfig.getEndpoint())
				.credentials(storageConfig.getAccessKeyId(), storageConfig.getAccessKeySecret())
				.build();
		String fileName = file.getName();
		String suffix = fileName.substring(fileName.lastIndexOf(".") + 1);
		String key = dir + UUID.randomUUID()+ "." + suffix;
		String type = URLConnection.guessContentTypeFromName(file.getName());
		try (FileInputStream in = new FileInputStream(file)) {
			PutObjectArgs.Builder args = PutObjectArgs.builder()
					.bucket(storageConfig.getBucket())
					.object(key)
					.stream(in, file.length(), -1)
					.contentType(type);
			if (InlineUnsafeTypes.isUnsafe(key)) {
				args.headers(Map.of("Content-Disposition", "attachment"));
			}
			minioClient.putObject(args.build());
		}
		return publicUrl(storageConfig.getEndpoint(), storageConfig.getBucket(), key);
	}

	/**
	 * 返回给前端 / 落库的访问地址 = Endpoint + "/" + Bucket + "/" + key。
	 *
	 * <p>Endpoint 带不带末尾 "/" 都拼成同一个结果（此前不带 "/" 时得到 {@code http://s3:9000bucket/...}，
	 * 2026-10-16 白纸部署验收 C-4）。⚠️ Endpoint 会原样出现在图片地址里，必须填浏览器能访问的外部地址
	 * （https 页面上要 https），不能填容器内地址。
	 */
	static String publicUrl(String endpoint, String bucket, String key) {
		String base = endpoint == null ? "" : endpoint.trim();
		while (base.endsWith("/")) {
			base = base.substring(0, base.length() - 1);
		}
		String b = bucket == null ? "" : bucket.trim();
		while (b.startsWith("/")) {
			b = b.substring(1);
		}
		while (b.endsWith("/")) {
			b = b.substring(0, b.length() - 1);
		}
		String k = key;
		while (k.startsWith("/")) {
			k = k.substring(1);
		}
		return base + "/" + b + "/" + k;
	}
}
