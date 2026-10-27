/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.admin.mapper.GoodsSpuMapper;
import com.zhiflow.mall.admin.service.GoodsSkuSpecValueService;
import com.zhiflow.mall.admin.service.ShopInfoService;
import com.zhiflow.mall.common.constant.MallConstants;
import com.zhiflow.mall.common.dto.SpuSpecDTO;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.GoodsSkuSpecValue;
import com.zhiflow.mall.common.entity.GoodsSpu;
import com.zhiflow.mall.common.entity.ShopInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

import java.lang.reflect.Constructor;
import java.util.Collection;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 后台保存商品的三处入参兜底：商品编码必填且不重复（库上有唯一索引，原先撞约束 500）；
 * 多规格编辑没带 spuSpec 时从 sku 的规格项推出（原先空指针）；规格值按新行插入（原先带回传主键撞 goods_sku_spec_value_pkey）。
 */
class GoodsSpuSaveValidationTest {

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
        ShopInfo shop = new ShopInfo();
        shop.setId(CommonConstants.DEFAULT_SHOP_ID);
        when(shopInfoService.getById(CommonConstants.DEFAULT_SHOP_ID)).thenReturn(shop);
        return s;
    }

    @Test
    @DisplayName("新增不填商品编码 → 明确提示，不落库")
    void blankSpuCodeRejected() throws Exception {
        GoodsSpuServiceImpl s = service();
        GoodsSpu spu = new GoodsSpu();
        spu.setName("无编码");
        assertThatThrownBy(() -> s.save1(spu)).isInstanceOf(BizException.class).hasMessage("请填写商品编码");
        verify(mapper, never()).insert(any(GoodsSpu.class));
    }

    @Test
    @DisplayName("商品编码已被占用 → 明确提示，不落库")
    void duplicateSpuCodeRejected() throws Exception {
        GoodsSpuServiceImpl s = service();
        when(mapper.exists(any())).thenReturn(true);
        GoodsSpu spu = new GoodsSpu();
        spu.setSpuCode(" A001 ");
        assertThatThrownBy(() -> s.save1(spu)).isInstanceOf(BizException.class).hasMessageContaining("A001").hasMessageContaining("已被其他商品使用");
        verify(mapper, never()).insert(any(GoodsSpu.class));
    }

    @Test
    @DisplayName("对照：编码未占用 → 去掉首尾空格后入库")
    void freeSpuCodeSaved() throws Exception {
        GoodsSpuServiceImpl s = service();
        GoodsSpu spu = new GoodsSpu();
        spu.setSpuCode(" A001 ");
        assertThat(s.save1(spu)).isTrue();
        verify(mapper).insert(spu);
        assertThat(spu.getSpuCode()).isEqualTo("A001");
    }

    @Test
    @DisplayName("编辑时请求里不带编码 → 不校验（表示不改）")
    void updateWithoutCodeSkipsCheck() throws Exception {
        GoodsSpuServiceImpl s = service();
        GoodsSpu spu = new GoodsSpu();
        spu.setId("1");
        s.checkSpuCode(spu, false);
        verify(mapper, never()).exists(any());
    }

    @Test
    @DisplayName("多规格没带 spuSpec → 按 sku 规格项首次出现顺序推出规格 id")
    void spuSpecDerivedFromSkus() throws Exception {
        GoodsSpu spu = multiSpec(sku("s1", spec("color", "white"), spec("size", "M")), sku("s2", spec("color", "black"), spec("size", "L")));
        service().requireSpuSpec(spu);
        assertThat(spu.getSpuSpec()).extracting(SpuSpecDTO::getId).containsExactly("color", "size");
    }

    @Test
    @DisplayName("多规格且 sku 上也没有规格项 → 明确提示，而不是把规格清空")
    void noSpecAnywhereRejected() throws Exception {
        GoodsSpu spu = multiSpec(sku("s1"));
        GoodsSpuServiceImpl s = service();
        assertThatThrownBy(() -> s.requireSpuSpec(spu)).isInstanceOf(BizException.class).hasMessageContaining("规格不能为空");
    }

    @Test
    @DisplayName("对照：带了 spuSpec 的原样保留")
    void givenSpuSpecKept() throws Exception {
        GoodsSpu spu = multiSpec(sku("s1", spec("color", "white")));
        SpuSpecDTO given = new SpuSpecDTO();
        given.setId("size");
        spu.setSpuSpec(List.of(given));
        service().requireSpuSpec(spu);
        assertThat(spu.getSpuSpec()).extracting(SpuSpecDTO::getId).containsExactly("size");
    }

    @Test
    @DisplayName("规格值落库前清掉回传的主键与审计字段，按新行插入")
    @SuppressWarnings("unchecked")
    void specValuesInsertedAsNewRows() throws Exception {
        GoodsSpuServiceImpl s = service();
        GoodsSkuSpecValue old = spec("color", "white");
        old.setId("2112882825414836225");
        old.setDelFlag("0");
        GoodsSpu spu = multiSpec(sku("s1", old));
        spu.setId("spu1");
        s.requireSpuSpec(spu);
        s.addSpec(spu);
        GoodsSkuSpecValueService values = (GoodsSkuSpecValueService) ReflectionTestUtils.getField(s, "goodsSkuSpecValueService");
        ArgumentCaptor<Collection<GoodsSkuSpecValue>> saved = ArgumentCaptor.forClass(Collection.class);
        verify(values).saveBatch(saved.capture());
        GoodsSkuSpecValue row = saved.getValue().iterator().next();
        assertThat(row.getId()).isNull();
        assertThat(row.getDelFlag()).isNull();
        assertThat(row.getSkuId()).isEqualTo("s1");
        assertThat(row.getSpuId()).isEqualTo("spu1");
    }

    private static GoodsSpu multiSpec(GoodsSku... skus) {
        GoodsSpu spu = new GoodsSpu();
        spu.setSpecType(MallConstants.SPU_SPEC_TYPE_1);
        spu.setSkus(List.of(skus));
        return spu;
    }

    private static GoodsSku sku(String id, GoodsSkuSpecValue... specs) {
        GoodsSku sku = new GoodsSku();
        sku.setId(id);
        sku.setSpecs(specs.length == 0 ? null : List.of(specs));
        return sku;
    }

    private static GoodsSkuSpecValue spec(String specId, String valueId) {
        GoodsSkuSpecValue v = new GoodsSkuSpecValue();
        v.setSpecId(specId);
        v.setSpecValueId(valueId);
        return v;
    }
}
