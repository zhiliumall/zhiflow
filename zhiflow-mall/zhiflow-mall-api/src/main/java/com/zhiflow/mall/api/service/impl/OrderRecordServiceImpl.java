package com.zhiflow.mall.api.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.api.mapper.OrderRecordMapper;
import com.zhiflow.mall.api.service.OrderRecordService;
import com.zhiflow.mall.common.entity.OrderRecord;
import com.zhiflow.mall.common.enums.OptSourceEnum;
import com.zhiflow.mall.common.enums.OrderRecordTypeEnum;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

@Service
public class OrderRecordServiceImpl extends ServiceImpl<OrderRecordMapper, OrderRecord> implements OrderRecordService {

    @Override
    public void saveOrderRecord(String orderId, OrderRecordTypeEnum recordType, OptSourceEnum recordSource, LocalDateTime paymentTime) {
        if (this.baseMapper.selectCount(new LambdaQueryWrapper<OrderRecord>().eq(OrderRecord::getOrderId, orderId).eq(OrderRecord::getRecordType, recordType.name())) == 0) {
            if (OrderRecordTypeEnum.CREATE.name().equals(recordType.name())) {
                //新增订单记录
                OrderRecord orderRecord = new OrderRecord();
                orderRecord.setOrderId(orderId);
                orderRecord.setRecordType(recordType.name());
                orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
                orderRecord.setRecordContent(recordType.getValue());
                this.baseMapper.insert(orderRecord);
            } else if (OrderRecordTypeEnum.PAY.name().equals(recordType.name())) {
                // 只记真实发生的事：付款完成。出库、预计送达等节点由商家发货与物流轨迹产生，不在这里预先编造
                OrderRecord orderRecord = new OrderRecord();
                orderRecord.setOrderId(orderId);
                orderRecord.setRecordType(recordType.name());
                orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
                orderRecord.setRecordContent(recordType.getValue());
                this.baseMapper.insert(orderRecord);

            } else if (OrderRecordTypeEnum.CANCEL.name().equals(recordType.name())) {
                OrderRecord orderRecord = new OrderRecord();
                orderRecord.setOrderId(orderId);
                orderRecord.setRecordType(recordType.name());
                orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
                orderRecord.setRecordContent(recordType.getValue());
                this.baseMapper.insert(orderRecord);

            } else if (OrderRecordTypeEnum.DELIVERY.name().equals(recordType.name())) {
                OrderRecord orderRecord = new OrderRecord();
                orderRecord.setOrderId(orderId);
                orderRecord.setRecordType(recordType.name());
                orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
                orderRecord.setRecordContent(recordType.getValue());
                this.baseMapper.insert(orderRecord);

            } else if (OrderRecordTypeEnum.RECEIVE.name().equals(recordType.name())) {
                OrderRecord orderRecord = new OrderRecord();
                orderRecord.setOrderId(orderId);
                orderRecord.setRecordType(recordType.name());
                orderRecord.setRecordSource(OptSourceEnum.defaultSource(recordSource).name());
                orderRecord.setRecordContent(recordType.getValue());
                this.baseMapper.insert(orderRecord);
            }

        }

    }
}
