package com.zhiflow.mall.api.payment;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.AbstractWrapper;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.zhiflow.mall.api.mapper.PayOrderMapper;
import com.zhiflow.mall.common.entity.PayOrder;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 商品订单的三处支付单查询只取商品订单的支付单（biz_type = ORDER），同一单号 / 订单 id 不会误取到别的业务类型。
 * 判据取 MyBatis-Plus 真拼出来的 WHERE 片段与绑定值（不是看源码）。
 */
class PayOrderServiceOrderScopeTest {

	private final List<Wrapper<PayOrder>> captured = new ArrayList<>();

	@BeforeAll
	static void initTableInfo() {
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		assistant.setCurrentNamespace("test");
		TableInfoHelper.initTableInfo(assistant, PayOrder.class);
	}

	@SuppressWarnings("unchecked")
	private PayOrderService service() {
		PayOrderMapper mapper = (PayOrderMapper) Proxy.newProxyInstance(getClass().getClassLoader(),
				new Class<?>[]{PayOrderMapper.class}, (p, m, a) -> {
					if ("selectList".equals(m.getName())) {
						captured.add((Wrapper<PayOrder>) a[0]);
						return List.of();
					}
					return m.getReturnType() == int.class ? 0 : null;
				});
		return new PayOrderService(mapper, null);
	}

	private static String where(Wrapper<PayOrder> w) {
		return w.getSqlSegment().replaceAll("\\s+", " ");
	}

	@SuppressWarnings("unchecked")
	private static Collection<Object> values(Wrapper<PayOrder> w) {
		return ((Map<String, Object>) ((AbstractWrapper<PayOrder, ?, ?>) w).getParamNameValuePairs()).values();
	}

	@Test
	@DisplayName("findLatestByOrderNoAndChannel 只取商品订单的支付单：WHERE 带 biz_type = ORDER")
	void findLatestOnlyGoods() {
		service().findLatestByOrderNoAndChannel("SO1", "8");

		Wrapper<PayOrder> w = captured.get(0);
		assertTrue(where(w).contains("order_no ="), "对照：订单号条件在——判据没坏：" + where(w));
		assertTrue(where(w).contains("biz_type ="), where(w));
		assertTrue(values(w).contains("ORDER"), values(w).toString());
	}

	@Test
	@DisplayName("closeOpenPayOrders 按订单 id 关本订单的支付单")
	void closeScopedToOrder() {
		service().closeOpenPayOrders("o1", "订单取消");

		Wrapper<PayOrder> w = captured.get(0);
		assertTrue(where(w).contains("biz_type =") && where(w).contains("biz_id ="), where(w));
		assertTrue(values(w).containsAll(List.of("ORDER", "o1")), values(w).toString());
	}

	@Test
	@DisplayName("listOrderPayOrders 按订单 id 取（不按会被改价重签的 order_no）；空 id 不查库")
	void listOrderPayOrders() {
		PayOrderService svc = service();
		svc.listOrderPayOrders("o1");

		Wrapper<PayOrder> w = captured.get(0);
		assertTrue(where(w).contains("biz_type =") && where(w).contains("biz_id ="), where(w));
		assertTrue(values(w).containsAll(List.of("ORDER", "o1")), values(w).toString());
		assertEquals(List.of(), svc.listOrderPayOrders(" "));
		assertEquals(1, captured.size());
	}
}
