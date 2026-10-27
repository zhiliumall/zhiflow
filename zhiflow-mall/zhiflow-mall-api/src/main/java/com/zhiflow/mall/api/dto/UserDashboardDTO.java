package com.zhiflow.mall.api.dto;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.util.Map;

/**
 * 用户面板数据传输对象
 */
@Data
public class UserDashboardDTO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

    /**
     * 用户基本信息
     */
    private UserInfo userInfo;

    /**
     * 订单统计信息:状态码 -> 数量。
     * 键集与散装 GET /orderinfo/countAll 完全一致("0"待付款/"1"待发货/"2"待收货/"3"待评价/"6"退款中),
     * 前端按"非空对象 map"校验该字段,list 形状会被视为降级产物而回退散装请求。
     * 查询失败时为空 map(缺数据即回退,不得伪造零值)。
     */
    private Map<String, Long> orderCountAll;

    /**
     * 用户信息子类
     */
    @Data
    public static class UserInfo {
        private String id;
        private String nickName;
        private String phone;
        private String headimgUrl;
    }

}
