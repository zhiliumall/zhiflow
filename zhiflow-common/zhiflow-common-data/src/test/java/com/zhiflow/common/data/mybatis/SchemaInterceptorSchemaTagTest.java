package com.zhiflow.common.data.mybatis;

import com.zhiflow.common.core.observability.TraceIds;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * span 属性 {@code db.schema} 从 SET 语句里取出的 schema：三个分支生成的语句文本各不相同
 * （普通租户分支有 "SET  " 双空格），都要认得出。
 */
class SchemaInterceptorSchemaTagTest {

	@Test
	@DisplayName("三个分支的 SET 语句都能取出第一个 schema")
	void schemaOfAllBranches() {
		assertEquals("zhiflow_mall_1", SchemaInterceptor.schemaOf("SET  search_path TO zhiflow_mall_1, public"));
		assertEquals("zhiflow_mall_1", SchemaInterceptor.schemaOf("SET search_path TO zhiflow_mall_1, public"));
		assertEquals("zhiflow_upms", SchemaInterceptor.schemaOf("SET search_path TO zhiflow_upms"));
	}

	@Test
	@DisplayName("认不出的文本返回 null")
	void unrecognised() {
		assertNull(SchemaInterceptor.schemaOf(null));
		assertNull(SchemaInterceptor.schemaOf("SELECT 1"));
		assertNull(SchemaInterceptor.schemaOf("SET search_path TO "));
	}

	@Test
	@DisplayName("没挂 OTel agent 时打标是空操作、不抛（默认部署形态）")
	void tagWithoutAgentIsNoop() {
		assertFalse(TraceIds.otelSpanValid());
		SchemaInterceptor.tagSchema("SET  search_path TO zhiflow_mall_1, public");
		SchemaInterceptor.tagSchema(null);
	}

}
