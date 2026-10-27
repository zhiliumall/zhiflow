package com.zhiflow.common.storage.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import static org.assertj.core.api.Assertions.assertThat;

/** C-4：Endpoint 带不带末尾 "/" 都要拼出同一个访问地址。 */
class MinioUtilsPublicUrlTest {

	@ParameterizedTest(name = "{0} + {1} + {2}")
	@CsvSource({
			"https://s3.example.com,  jwl, 1/material/a.png, https://s3.example.com/jwl/1/material/a.png",
			"https://s3.example.com/, jwl, 1/material/a.png, https://s3.example.com/jwl/1/material/a.png",
			"https://s3.example.com//, /jwl/, /1/material/a.png, https://s3.example.com/jwl/1/material/a.png",
			"' https://s3.example.com:9000 ', jwl, 1/a.png, https://s3.example.com:9000/jwl/1/a.png",
	})
	@DisplayName("Endpoint / Bucket 的首尾斜杠都被规范化")
	void joinsWithSingleSlash(String endpoint, String bucket, String key, String expected) {
		assertThat(MinioUtils.publicUrl(endpoint, bucket, key)).isEqualTo(expected);
	}
}
