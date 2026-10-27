package com.zhiflow.mall.admin.service.impl;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.mall.admin.mapper.GoodsParameterMapper;
import com.zhiflow.mall.admin.mapper.GoodsParameterValueMapper;
import com.zhiflow.mall.admin.mapper.GoodsParameterTemplateMapper;
import com.zhiflow.mall.admin.service.GoodsParameterTemplateService;
import com.zhiflow.mall.common.entity.*;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.util.List;

@AllArgsConstructor
@Service
public class GoodsParameterTemplateServiceImpl extends ServiceImpl<GoodsParameterTemplateMapper, GoodsParameterTemplate> implements GoodsParameterTemplateService {

    private final GoodsParameterValueMapper goodsParameterValueMapper;
    private final GoodsParameterMapper goodsParameterMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeById(Serializable id) {

        List<GoodsParameter> goodsSpecs = goodsParameterMapper.selectList(Wrappers.<GoodsParameter>query().
                lambda()
                .eq(GoodsParameter::getParameterTemplateId, id));
        List<String> list = goodsSpecs.stream().map(GoodsParameter::getId).toList();
        if (!list.isEmpty()) {
            goodsParameterMapper.deleteBatchIds(list);
            goodsParameterValueMapper.delete(Wrappers.<GoodsParameterValue>update().lambda()
                    .in(GoodsParameterValue::getParameterId, list));
        }
        return super.removeById(id);
    }
}
