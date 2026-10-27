package com.zhiflow.mall.api.mapper;

import com.zhiflow.mall.api.dto.UserDashboardDTO;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.Map;

/**
 * 用户面板数据访问层
 */
@Mapper
public interface UserDashboardMapper {

    /**
     * 获取用户基本信息
     */
    UserDashboardDTO.UserInfo getUserInfo(@Param("userId") String userId);

    /**
     * 获取订单统计信息。
     * 单行返回,键为订单状态("0"待付款/"1"待发货/"2"待收货/"3"待评价/"6"退款中),
     * 与 XML 里的数字列别名一一对应。不能用按属性名映射的 DTO 作 resultType:
     * 数字别名映射不上任何属性时 MyBatis 对整行返回 null。
     */
    Map<String, Object> getOrderCountAll(@Param("userId") String userId);

}