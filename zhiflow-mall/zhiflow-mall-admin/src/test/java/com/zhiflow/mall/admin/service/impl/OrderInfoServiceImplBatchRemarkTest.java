package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.mall.admin.mapper.OrderInfoMapper;
import com.zhiflow.mall.common.dto.OrderRemarkBatchRequest;
import com.zhiflow.mall.common.dto.OrderRemarkBatchResult;
import com.zhiflow.mall.common.entity.OrderInfo;
import org.junit.jupiter.api.Test;
import org.springframework.objenesis.ObjenesisStd;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OrderInfoServiceImplBatchRemarkTest {

    @Test
    void batchRemarkReturnsUpdatedUnchangedAndNonEnumeratingFailure() {
        OrderInfoMapper mapper = mock(OrderInfoMapper.class);
        when(mapper.selectById("1")).thenReturn(order("1", "shop-a", "old"));
        when(mapper.selectById("2")).thenReturn(order("2", "shop-a", "same"));
        when(mapper.selectById("3")).thenReturn(order("3", "shop-b", "old"));
        when(mapper.updateById(any(OrderInfo.class))).thenReturn(1);

        OrderRemarkBatchResult result = service(mapper).batchRemark(request(List.of("1", "2", "3", "1"), "same"), "shop-a");

        assertEquals(3, result.getRequested());
        assertEquals(1, result.getUpdated());
        assertEquals(1, result.getUnchanged());
        assertEquals(1, result.getFailed());
        assertEquals("UPDATED", result.getResults().get(0).getStatus());
        assertEquals("UNCHANGED", result.getResults().get(1).getStatus());
        assertEquals("ORDER_NOT_FOUND_OR_FORBIDDEN", result.getResults().get(2).getCode());
    }

    @Test
    void batchRemarkKeepsLaterItemsWhenOneOrderWriteThrows() {
        OrderInfoMapper mapper = mock(OrderInfoMapper.class);
        when(mapper.selectById("1")).thenReturn(order("1", "shop-a", "old"));
        when(mapper.selectById("2")).thenReturn(order("2", "shop-a", "old"));
        when(mapper.updateById(any(OrderInfo.class))).thenThrow(new IllegalStateException("db transient")).thenReturn(1);

        OrderRemarkBatchResult result = service(mapper).batchRemark(request(List.of("1", "2"), "new"), "shop-a");

        assertEquals(1, result.getFailed());
        assertEquals(1, result.getUpdated());
        assertEquals("ORDER_UPDATE_FAILED", result.getResults().get(0).getCode());
        assertEquals("UPDATED", result.getResults().get(1).getStatus());
    }

    private static OrderInfoServiceImpl service(OrderInfoMapper mapper) {
        OrderInfoServiceImpl service = new ObjenesisStd().newInstance(OrderInfoServiceImpl.class);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        return service;
    }

    private static OrderRemarkBatchRequest request(List<String> ids, String remark) {
        OrderRemarkBatchRequest request = new OrderRemarkBatchRequest();
        request.setOrderIds(ids);
        request.setRemark(remark);
        return request;
    }

    private static OrderInfo order(String id, String shopId, String remark) {
        OrderInfo order = new OrderInfo();
        order.setId(id);
        order.setShopId(shopId);
        order.setRemark(remark);
        order.setDelFlag("0");
        return order;
    }
}
