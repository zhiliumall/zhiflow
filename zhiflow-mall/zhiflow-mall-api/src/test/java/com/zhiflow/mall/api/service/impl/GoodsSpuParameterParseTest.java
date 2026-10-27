package com.zhiflow.mall.api.service.impl;

import com.zhiflow.mall.api.vo.GoodsParameterItem;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商详参数区解析：后台商品表单保存的 {@code parameter_json} 形态是 {@code {"id", "data": [{name, type, value}]}}，
 * 输入框 / 单选的 value 是字符串，多选的 value 是数组。
 */
class GoodsSpuParameterParseTest {

	@Test
	@DisplayName("输入框、单选原样；多选用「、」拼；保持录入顺序")
	void parsesAllThreeTypes() {
		String json = "{\"id\":\"T1\",\"data\":["
				+ "{\"name\":\"材质\",\"type\":2,\"value\":\"纯棉\"},"
				+ "{\"name\":\"适用季节\",\"type\":1,\"value\":[\"春\",\"秋\"]},"
				+ "{\"name\":\"版型\",\"type\":0,\"value\":\"宽松\"}]}";

		assertEquals(List.of(new GoodsParameterItem("材质", "纯棉"), new GoodsParameterItem("适用季节", "春、秋"),
				new GoodsParameterItem("版型", "宽松")), GoodsSpuServiceImpl.parseParameters(json));
	}

	@Test
	@DisplayName("没填值、空数组、null、没名称的行都不展示")
	void skipsEmptyRows() {
		String json = "{\"data\":[{\"name\":\"产地\",\"value\":\"\"},{\"name\":\"颜色\",\"value\":[]},"
				+ "{\"name\":\"尺码\",\"value\":null},{\"value\":\"孤值\"},{\"name\":\" 重量 \",\"value\":\" 200g \"}]}";

		assertEquals(List.of(new GoodsParameterItem("重量", "200g")), GoodsSpuServiceImpl.parseParameters(json));
	}

	@Test
	@DisplayName("空串、非对象、缺 data、坏 JSON：返回空列表，不让商详页报错")
	void toleratesUnknownShapes() {
		assertTrue(GoodsSpuServiceImpl.parseParameters(null).isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("").isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("[1,2]").isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("{\"id\":\"T1\"}").isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("{\"data\":\"oops\"}").isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("not json").isEmpty());
		assertTrue(GoodsSpuServiceImpl.parseParameters("{oops").isEmpty());
	}
}
