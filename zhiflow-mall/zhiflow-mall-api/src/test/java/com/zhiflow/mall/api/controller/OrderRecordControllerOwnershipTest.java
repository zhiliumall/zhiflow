package com.zhiflow.mall.api.controller;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.service.OrderInfoService;
import com.zhiflow.mall.api.service.OrderRecordService;
import com.zhiflow.mall.common.constant.MallReturnCode;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.entity.OrderRecord;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * C 组 M5：{@code GET /mallapi/orderRecord/{orderId}}，以及同形的 {@code POST /mallapi/orderRecord/batch}。
 *
 * <p>两者此前都按请求里的订单 id 原样查：任何登录用户可读别人订单的操作时间线。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OrderRecordControllerOwnershipTest {

	@Mock
	private OrderRecordService orderRecordService;

	@Mock
	private OrderInfoService orderInfoService;

	@InjectMocks
	private OrderRecordController controller;

	/** 「库里」的全部时间线：本人订单一条、别人订单一条。 */
	private final List<OrderRecord> db = List.of(record("R-mine", "O-mine"), record("R-other", "O-other"));

	@BeforeAll
	static void initMybatisPlusLambdaCache() {
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		assistant.setCurrentNamespace("test");
		TableInfoHelper.initTableInfo(assistant, OrderRecord.class);
	}

	@BeforeEach
	@SuppressWarnings("unchecked")
	void setUp() {
		ThirdSession s = new ThirdSession();
		s.setUserId("me");
		ThirdSessionHolder.setThirdSession(s);

		when(orderInfoService.getById("O-mine")).thenReturn(order("O-mine", "me"));
		when(orderInfoService.getById("O-other")).thenReturn(order("O-other", "someone-else"));
		when(orderInfoService.listByIds(anyCollection())).thenAnswer(inv -> {
			Collection<?> ids = inv.getArgument(0);
			return List.of(order("O-mine", "me"), order("O-other", "someone-else")).stream()
					.filter(o -> ids.contains(o.getId())).toList();
		});
		// 模拟库：只返回查询条件里出现过的订单 id 的时间线——服务端拼进查询的 id 就是能读到的范围
		when(orderRecordService.list(any(Wrapper.class))).thenAnswer(inv -> {
			AbstractWrapper<?, ?, ?> wrapper = inv.getArgument(0);
			wrapper.getSqlSegment(); // 参数是渲染 SQL 时才落进 paramNameValuePairs 的，先渲染一次
			Collection<Object> params = wrapper.getParamNameValuePairs().values();
			return db.stream().filter(r -> params.contains(r.getOrderId())).toList();
		});
	}

	@AfterEach
	void logout() {
		ThirdSessionHolder.clear();
	}

	private static OrderInfo order(String id, String userId) {
		OrderInfo o = new OrderInfo();
		o.setId(id);
		o.setUserId(userId);
		return o;
	}

	private static OrderRecord record(String id, String orderId) {
		OrderRecord r = new OrderRecord();
		r.setId(id);
		r.setOrderId(orderId);
		return r;
	}

	@Test
	@DisplayName("单条：别人的订单 → ERR_70001，不查时间线")
	@SuppressWarnings("unchecked")
	void single_othersOrderRejected() {
		R<List<OrderRecord>> r = controller.getByOrderId("O-other");
		assertEquals(MallReturnCode.ERR_70001.getCode(), r.getCode());
		assertNull(r.getData());
		verify(orderRecordService, never()).list(any(Wrapper.class));
	}

	@Test
	@DisplayName("单条：本人订单 → 照常返回")
	void single_ownOrderReturned() {
		R<List<OrderRecord>> r = controller.getByOrderId("O-mine");
		assertEquals(0, r.getCode());
		assertEquals(1, r.getData().size());
		assertEquals("R-mine", r.getData().get(0).getId());
	}

	@Test
	@DisplayName("单条：不存在的订单 → 仍回空列表，与原行为一致")
	void single_unknownOrderEmpty() {
		R<List<OrderRecord>> r = controller.getByOrderId("O-ghost");
		assertEquals(0, r.getCode());
		assertTrue(r.getData().isEmpty());
	}

	@Test
	@DisplayName("批量：混入别人的订单 id → 只回本人的；别人的 / 不存在的仍占 key、值为空（形状与「没有日志」相同）")
	void batch_onlyOwnOrdersQueried() {
		R<Map<String, List<OrderRecord>>> r = controller.batchGetByOrderIds(List.of("O-mine", "O-other", "O-ghost"));
		assertEquals(0, r.getCode());
		Map<String, List<OrderRecord>> data = r.getData();
		assertEquals(3, data.size());
		assertEquals(1, data.get("O-mine").size());
		assertTrue(data.get("O-other").isEmpty(), "别人订单的时间线不能出现");
		assertTrue(data.get("O-ghost").isEmpty());
	}

	@Test
	@DisplayName("批量：全是别人的 → 不查时间线，全部空列表")
	@SuppressWarnings("unchecked")
	void batch_noneOwned_noQuery() {
		R<Map<String, List<OrderRecord>>> r = controller.batchGetByOrderIds(List.of("O-other"));
		assertEquals(0, r.getCode());
		assertTrue(r.getData().get("O-other").isEmpty());
		verify(orderRecordService, never()).list(any(Wrapper.class));
	}
}
