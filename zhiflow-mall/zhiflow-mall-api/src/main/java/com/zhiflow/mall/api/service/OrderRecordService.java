package com.zhiflow.mall.api.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.OrderRecord;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderRecordTypeEnum;

import java.time.LocalDateTime;

public interface OrderRecordService extends IService<OrderRecord> {

    /**
     * 保存订单记录
     * @param orderId
     * @param recordType
     * @param recordSource
     */
     void saveOrderRecord(String orderId, OrderRecordTypeEnum recordType, OptSourceEnum recordSource, LocalDateTime paymentTime);
}
