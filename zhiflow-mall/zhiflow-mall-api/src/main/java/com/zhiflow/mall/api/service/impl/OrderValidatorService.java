/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.common.entity.GoodsSku;
import com.zhiflow.mall.common.entity.OrderItem;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * 订单项算价：金额一律由服务端按 SKU 售价算，<b>不读客户端传的任何金额</b>。
 *
 * <p>开源版没有优惠券、积分、会员折扣、营销活动，运费固定为 0，所以
 * 商品项实付 = SKU 售价 × 数量。客户端传上来的 paymentPrice / 各项优惠 / 运费一律被覆盖，
 * 篡改报文改不了价。
 *
 * @date 2025-01-17
 */
@Service
public class OrderValidatorService {

    /**
     * 校验数量并按 SKU 售价重算订单项金额（就地覆盖 orderItem 上的金额字段）。
     *
     * @param orderItem 订单项
     * @param goodsSku  从库里读出的 SKU（售价以它为准）
     */
    public void priceOrderItem(OrderItem orderItem, GoodsSku goodsSku) {
        if (orderItem.getQuantity() == null || orderItem.getQuantity() < 1) {
            throw new BizException("购买数量不正确：" + orderItem.getQuantity());
        }
        BigDecimal salesPrice = goodsSku.getSalesPrice();
        if (salesPrice == null || salesPrice.signum() < 0) {
            throw new BizException("商品价格异常，请刷新后重试");
        }
        orderItem.setSalesPrice(salesPrice);
        orderItem.setPaymentPrice(salesPrice.multiply(BigDecimal.valueOf(orderItem.getQuantity()))
                .setScale(2, RoundingMode.HALF_UP));
        orderItem.setFreightPrice(BigDecimal.ZERO);
        orderItem.setPaymentCouponPrice(BigDecimal.ZERO);
        orderItem.setPaymentPointsPrice(BigDecimal.ZERO);
        orderItem.setPaymentPoints(0);
    }
}
