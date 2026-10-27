package com.zhiflow.mall.admin.service.impl;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.mall.common.entity.OrderInfo;
import com.zhiflow.mall.common.enums.OrderInfoEnum;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 支付状态机阶段 0 · 0.4：后台发货的状态校验。
 *
 * <p>此前 delivery 从头到尾不读 status / is_pay，只有前端按钮挡着：已取消、已全退、未付的单都能被改成
 * 「待收货」，7 天后自动收货并发成长值和收货奖励。
 */
class OrderInfoServiceImplDeliveryGuardTest {

    private static OrderInfo order(String status, String isPay) {
        OrderInfo o = new OrderInfo();
        o.setId("o1");
        o.setStatus(status);
        o.setIsPay(isPay);
        return o;
    }

    @Test
    void paidPendingShipmentCanShip() {
        assertDoesNotThrow(() -> OrderInfoServiceImpl.assertDeliverable(
                order(OrderInfoEnum.STATUS_1.getValue(), CommonConstants.YES), null));
    }

    @Test
    void shippedOrderCanOnlyEditExistingLogistics() {
        assertDoesNotThrow(() -> OrderInfoServiceImpl.assertDeliverable(
                order(OrderInfoEnum.STATUS_2.getValue(), CommonConstants.YES), "logistics-1"));
        assertThrows(BizException.class, () -> OrderInfoServiceImpl.assertDeliverable(
                order(OrderInfoEnum.STATUS_2.getValue(), CommonConstants.YES), ""));
    }

    @Test
    void unpaidOrderCannotShip() {
        assertThrows(BizException.class, () -> OrderInfoServiceImpl.assertDeliverable(
                order(null, CommonConstants.NO), null));
        assertThrows(BizException.class, () -> OrderInfoServiceImpl.assertDeliverable(
                order(OrderInfoEnum.STATUS_1.getValue(), CommonConstants.NO), null));
    }

    @Test
    void cancelledCompletedGroupingOrAuditOrdersCannotShip() {
        for (String s : new String[]{"3", "5", "7", "10", null}) {
            assertThrows(BizException.class, () -> OrderInfoServiceImpl.assertDeliverable(
                    order(s, CommonConstants.YES), null), "不该允许发货的状态: " + s);
        }
    }

    @Test
    void missingOrderIsRejected() {
        assertThrows(BizException.class, () -> OrderInfoServiceImpl.assertDeliverable(null, null));
    }
}
