package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.service.ShopInfoService;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShopInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 新建商品的店铺归属（默认店铺）：表单没传 shopId 时落到 {@link CommonConstants#DEFAULT_SHOP_ID}，不再报错；
 * 传了但店铺不存在仍是明确的业务错误、不落库（原先直接 NPE → 500「shopInfo is null」）。
 */
class GoodsSpuSaveShopGuardTest {

    private final ShopInfoService shopInfoService = mock(ShopInfoService.class);
    private final GoodsSpuMapper mapper = mock(GoodsSpuMapper.class);

    private GoodsSpuServiceImpl service() throws Exception {
        Constructor<?> ctor = GoodsSpuServiceImpl.class.getDeclaredConstructors()[0];
        Object[] args = new Object[ctor.getParameterCount()];
        Class<?>[] types = ctor.getParameterTypes();
        for (int i = 0; i < types.length; i++) {
            args[i] = types[i] == ShopInfoService.class ? shopInfoService : mock(types[i]);
        }
        GoodsSpuServiceImpl s = (GoodsSpuServiceImpl) ctor.newInstance(args);
        ReflectionTestUtils.setField(s, "baseMapper", mapper);
        return s;
    }

    private void defaultShopExists() {
        ShopInfo shop = new ShopInfo();
        shop.setId(CommonConstants.DEFAULT_SHOP_ID);
        when(shopInfoService.getById(CommonConstants.DEFAULT_SHOP_ID)).thenReturn(shop);
    }

    @Test
    @DisplayName("save1：shopId 为空 → 落到默认店铺并入库")
    void blankShopIdFallsBackToDefaultShop() throws Exception {
        defaultShopExists();
        GoodsSpu spu = new GoodsSpu();
        spu.setName("测试商品");
        spu.setShopId("");
        spu.setSpuCode("T001");
        assertThat(service().save1(spu)).isTrue();
        ArgumentCaptor<GoodsSpu> saved = ArgumentCaptor.forClass(GoodsSpu.class);
        verify(mapper).insert(saved.capture());
        assertThat(saved.getValue().getShopId()).isEqualTo(CommonConstants.DEFAULT_SHOP_ID);
    }

    @Test
    @DisplayName("对照：shopId 为空且默认店铺不在库里 → 明确提示，不落库（证明上一条确实查了默认店铺）")
    void blankShopIdWithoutDefaultShopRejected() throws Exception {
        GoodsSpu spu = new GoodsSpu();
        spu.setName("测试商品");
        assertThatThrownBy(() -> service().save1(spu))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
        verify(mapper, never()).insert(any(GoodsSpu.class));
    }

    @Test
    @DisplayName("save1：shopId 指向不存在的店铺 → 明确提示，不落库")
    void unknownShopRejected() throws Exception {
        defaultShopExists();
        GoodsSpu spu = new GoodsSpu();
        spu.setShopId("404");
        assertThatThrownBy(() -> service().save1(spu))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("店铺不存在");
        verify(mapper, never()).insert(any(GoodsSpu.class));
    }

    @Test
    @DisplayName("saveBatch1：缺 shopId 的条目同样落到默认店铺")
    void batchBlankShopIdFallsBackToDefaultShop() throws Exception {
        defaultShopExists();
        GoodsSpu spu = new GoodsSpu();
        spu.setName("批量商品");
        spu.setSpuCode("T002");
        service().saveBatch1(List.of(spu));
        assertThat(spu.getShopId()).isEqualTo(CommonConstants.DEFAULT_SHOP_ID);
        verify(mapper).insert(spu);
    }
}
