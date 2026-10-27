package com.zhiflow.mall.api.service.impl;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.api.mapper.GoodsSkuMapper;
import com.zhiflow.mall.api.service.ShoppingCartService;
import com.zhiflow.mall.api.service.UserAddressService;
import com.zhiflow.mall.api.service.UserInfoService;
import com.zhiflow.mall.common.dto.PlaceOrderDTO;
import com.zhiflow.mall.common.dto.PlaceOrderSkuDTO;
import com.zhiflow.mall.common.entity.UserAddress;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * 下单收货地址归属：拿别人的地址 id 下单会在下单响应和订单详情里读到对方的姓名、电话、详细地址。
 *
 * <p>10-22 下单链路清理：控制器 {@code /orderinfo/map} 核过的地址随 DTO（{@code resolvedShippingAddress}）带进
 * {@code orderAddOne} 复用，事务里不再按 userAddressId 查第二次；只有「属于下单人且 id 与请求一致」才复用，否则照旧查库核验。
 */
@ExtendWith(MockitoExtension.class)
class OrderShippingAddressOwnershipTest {

    private static final String ME = "me-001";

    @Mock
    private UserAddressService userAddressService;
    @Mock
    private UserInfoService userInfoService;
    @Mock
    private GoodsSkuMapper goodsSkuMapper;
    @Mock
    private ShoppingCartService shoppingCartService;

    @InjectMocks
    private OrderInfoServiceImpl service;

    private void givenAddress(String id, String owner) {
        when(userAddressService.getById(id)).thenReturn(address(id, owner));
    }

    private static UserAddress address(String id, String owner) {
        UserAddress a = new UserAddress();
        a.setId(id);
        a.setUserId(owner);
        return a;
    }

    private static PlaceOrderDTO order(String userId, String addressId) {
        PlaceOrderDTO dto = new PlaceOrderDTO();
        dto.setUserId(userId);
        dto.setUserAddressId(addressId);
        dto.setShopId("shop-1");
        dto.setAppId("app-1");
        PlaceOrderSkuDTO sku = new PlaceOrderSkuDTO();
        sku.setSkuId("sku-1");
        sku.setSpuId("spu-1");
        sku.setQuantity(1);
        dto.setSkus(List.of(sku));
        return dto;
    }

    // ---------------- requireOwnShippingAddress ----------------

    @Test
    @DisplayName("自己的地址：通过，返回该地址")
    void ownAddressPasses() {
        givenAddress("addr-1", ME);

        UserAddress got = service.requireOwnShippingAddress(order(ME, "addr-1"));

        assertEquals("addr-1", got.getId());
    }

    @Test
    @DisplayName("别人的地址：拒绝「无权操作」")
    void someoneElsesAddressRejected() {
        givenAddress("addr-9", "other-9");

        BizException e = assertThrows(BizException.class,
                () -> service.requireOwnShippingAddress(order(ME, "addr-9")));

        assertEquals("无权操作", e.getMessage());
    }

    @Test
    @DisplayName("没带地址 / 地址不存在：「请选择收货地址」")
    void missingAddressRejected() {
        BizException blank = assertThrows(BizException.class,
                () -> service.requireOwnShippingAddress(order(ME, null)));
        BizException unknown = assertThrows(BizException.class,
                () -> service.requireOwnShippingAddress(order(ME, "nope")));

        assertEquals("请选择收货地址", blank.getMessage());
        assertEquals("请选择收货地址", unknown.getMessage());
    }

    // ---------------- orderAddOne：拒单发生在任何写库之前 ----------------

    @Test
    @DisplayName("orderAddOne 带别人的地址：抛「无权操作」，查 SKU / 购物车等后续步骤一步都不走")
    void orderAddOneRejectsBeforeAnyWrite() {
        givenAddress("addr-9", "other-9");

        BizException e = assertThrows(BizException.class, () -> service.orderAddOne(null, order(ME, "addr-9")));

        assertEquals("无权操作", e.getMessage());
        verifyNoInteractions(goodsSkuMapper, shoppingCartService);
    }

    @Test
    @DisplayName("orderAddOne 地址属于下单人：放行进入建单（这里 SKU 查不到，在后面中断，不影响结论）")
    void orderAddOnePassesOwnAddressThrough() {
        givenAddress("addr-1", ME);

        BizException e = assertThrows(BizException.class, () -> service.orderAddOne(null, order(ME, "addr-1")));

        assertEquals("商品不存在或库存不足", e.getMessage());
        verify(goodsSkuMapper).listSkuInOrderSub(any(), any());
        verifyNoInteractions(shoppingCartService);
    }

    // ---------------- 10-22：控制器核过的地址随 DTO 带进 orderAddOne，事务里不再查第二次 ----------------

    @Test
    @DisplayName("DTO 带着已核过、属于下单人且 id 一致的地址：orderAddOne 直接复用，不再查库")
    void orderAddOneReusesResolvedAddress() {
        PlaceOrderDTO dto = order(ME, "addr-1");
        dto.setResolvedShippingAddress(address("addr-1", ME));

        BizException e = assertThrows(BizException.class, () -> service.orderAddOne(null, dto));

        assertEquals("商品不存在或库存不足", e.getMessage());
        verifyNoInteractions(userAddressService);
    }

    @Test
    @DisplayName("DTO 里的地址不属于下单人：不复用，照旧查库核归属并拒单")
    void resolvedAddressOfAnotherUserIsIgnored() {
        PlaceOrderDTO dto = order(ME, "addr-9");
        dto.setResolvedShippingAddress(address("addr-9", "other-9"));
        givenAddress("addr-9", "other-9");

        BizException e = assertThrows(BizException.class, () -> service.orderAddOne(null, dto));

        assertEquals("无权操作", e.getMessage());
        verify(userAddressService).getById("addr-9");
        verifyNoInteractions(goodsSkuMapper);
    }

    @Test
    @DisplayName("DTO 里的地址 id 与请求的 userAddressId 不一致：不复用，按请求里的 id 查库核验")
    void resolvedAddressWithDifferentIdIsIgnored() {
        PlaceOrderDTO dto = order(ME, "addr-9");
        dto.setResolvedShippingAddress(address("addr-1", ME));
        givenAddress("addr-9", "other-9");

        BizException e = assertThrows(BizException.class, () -> service.orderAddOne(null, dto));

        assertEquals("无权操作", e.getMessage());
        verify(userAddressService).getById("addr-9");
        verifyNoInteractions(goodsSkuMapper);
    }

    @Test
    @DisplayName("resolvedShippingAddress 是服务端内部字段：JsonIgnore，请求体带不进来")
    void resolvedAddressIsJsonIgnored() throws Exception {
        assertTrue(PlaceOrderDTO.class.getDeclaredField("resolvedShippingAddress")
                .isAnnotationPresent(com.fasterxml.jackson.annotation.JsonIgnore.class));
    }
}
