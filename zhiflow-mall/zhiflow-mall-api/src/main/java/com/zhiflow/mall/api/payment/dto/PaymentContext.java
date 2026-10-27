package com.zhiflow.mall.api.payment.dto;

import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 发起支付的入参（业务侧 → 编排层 → 渠道）。
 *
 * <p>刻意**不携带任何渠道私有字段**：渠道需要的额外东西从 {@code pay_config} 或
 * {@link #extra} 里取。这样业务方（订单/会员/余额/收银台/保证金）只认这一个上下文，
 * 不必知道自己在跟哪条渠道说话。
 *
 * <p><b>金额由服务端计算后填入，绝不接受客户端传入。</b>
 */
@Data
@Builder
public class PaymentContext {

    /** 业务类型，取值见 {@code PayBizTypeEnum}。 */
    private String bizType;

    /** 业务单 id（订单 id / 充值单 id …）。 */
    private String bizId;

    /** 关联业务订单号，仅作关联与排查用；渠道侧一律用 payNo。 */
    private String orderNo;

    /** 渠道码，与 {@code pay_config.type} 同值。 */
    private String channelCode;

    /** 支付金额（服务端计算，numeric(10,2) 口径）。 */
    private BigDecimal amount;

    /** 币种，默认 CNY。 */
    private String currCode;

    /** 商品/订单描述，出现在顾客的支付账单上。 */
    private String subject;

    private String tenantId;

    private String userId;

    /** 支付运行环境：MA 小程序 / H5-WX 微信内H5 / H5 普通浏览器 / APP。 */
    private String appType;

    private String appId;

    /** 渠道需要的用户标识（微信 openid 等）。 */
    private String payOpenId;

    /** 支付完成后浏览器跳回的地址。**仅用于展示，绝不作为入账依据。** */
    private String returnUrl;

    /** 用户中途放弃时跳回的地址。 */
    private String quitUrl;

    private String clientIp;

    /** 渠道私有的补充参数，避免为单个渠道往本类加字段。 */
    private Map<String, Object> extra;

    /** {@link #extra} 里「顾客选定的具体支付方式」的键（供托管收银台类渠道区分子支付方式）。 */
    public static final String EXTRA_PAY_METHOD = "payMethod";
}
