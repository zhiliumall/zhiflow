package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.admin.mapper.OrderRecordMapper;
import com.zhiflow.mall.admin.service.OrderRecordService;
import com.zhiflow.mall.common.entity.OrderRecord;
import org.springframework.stereotype.Service;


@Service
public class OrderRecordServiceImpl extends ServiceImpl<OrderRecordMapper, OrderRecord> implements OrderRecordService {

}