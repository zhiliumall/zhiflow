package com.zhiflow.mall.admin.service;

import com.baomidou.mybatisplus.extension.service.IService;
import com.zhiflow.mall.common.entity.GoodsParameter;

import java.util.List;

public interface GoodsParameterService extends IService<GoodsParameter> {
    List<GoodsParameter> list1(GoodsParameter goodsParameter);
}
