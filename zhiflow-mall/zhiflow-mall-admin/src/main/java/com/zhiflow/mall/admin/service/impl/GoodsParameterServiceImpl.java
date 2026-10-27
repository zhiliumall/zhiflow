package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.admin.mapper.GoodsParameterMapper;
import com.zhiflow.mall.admin.mapper.GoodsParameterValueMapper;
import com.zhiflow.mall.admin.service.GoodsParameterService;
import com.zhiflow.mall.common.entity.GoodsParameter;
import com.zhiflow.mall.common.entity.GoodsParameterValue;
import com.zhiflow.mall.common.entity.GoodsSpecValue;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

@Service
public class GoodsParameterServiceImpl extends ServiceImpl<GoodsParameterMapper, GoodsParameter> implements GoodsParameterService {
    @Autowired
    private GoodsParameterValueMapper goodsParameterValueMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {
        goodsParameterValueMapper.delete(Wrappers.<GoodsParameterValue>update().lambda()
                .eq(GoodsParameterValue::getParameterId, id));
        return super.removeById(id);
    }

    @Override
    public List<GoodsParameter> list1(GoodsParameter goodsParameter) {
        LambdaQueryWrapper<GoodsParameter> like = new LambdaQueryWrapper<GoodsParameter>()
                .eq(Objects.nonNull(goodsParameter.getParameterTemplateId()), GoodsParameter::getParameterTemplateId, goodsParameter.getParameterTemplateId())
                .like(StringUtils.isNotBlank(goodsParameter.getName()), GoodsParameter::getName, goodsParameter.getName())
                .orderByAsc(GoodsParameter::getSort);
        List<GoodsParameter> list = this.list(like);
        list.forEach(a -> a.setGoodsParameterValueList(goodsParameterValueMapper.selectList(Wrappers.<GoodsParameterValue>query()
                .lambda()
                .eq(GoodsParameterValue::getParameterId, a.getId())))
        );
        return list;
    }
}
