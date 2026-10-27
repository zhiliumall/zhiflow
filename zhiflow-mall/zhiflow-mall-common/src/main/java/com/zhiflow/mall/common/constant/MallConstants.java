package com.zhiflow.mall.common.constant;

/**
 * @author
 */
public interface MallConstants {

	/**
	 * 商品规格类型：0统一规格；1多规格
	 */
	String SPU_SPEC_TYPE_0 = "0";
	/**
	 * 商品规格类型：0统一规格；1多规格
	 */
	String SPU_SPEC_TYPE_1 = "1";
	/**
	 * 订单自动取消时间（分钟）
	 */
	long ORDER_TIME_OUT_0 = 30;
	/**
	 * 订单自动收货时间（天）
	 */
	long ORDER_TIME_OUT_2 = 7;
	/**
	 * 支付方式1、货到付款；2、在线支付
	 */
	String PAYMENT_WAY_2 = "2";

	String ORDER_TYPE_0 = "0";

	/**
	 * 配送方式：只有普通快递（1）
	 */
	String DELIVERY_WAY_1 = "1";

	/**
	 * 商品审核状态（0审核中 1审核通过 2审核不通过）
	 */
	String GOODS_STATUS_0 = "0";
	String GOODS_STATUS_1 = "1";

 	/**
 	 * 订单支付类型：1 微信支付，2 支付宝支付
 	 */
 	String ORDER_PAYMENT_TYPE_1 = "1";
 	String ORDER_PAYMENT_TYPE_2 = "2";
	/**
	 * 服务路由名
	 */
	String SERVER_ROUTE_NAME_MALLAPI = "/mallapi";

	/**
	 * 发货类型（1、发物流 2、配送 3、虚拟发货）
	 */
	String DELIVERY_TYPE_1 = "1";

	/**
	 * 客户端类型：小程序，普通H5，微信H5，APPapp，pcH5；UI：后台UI）
	 */
	String CLIENT_TYPE_MA = "MA";
	String CLIENT_TYPE_H5 = "H5";
	String CLIENT_TYPE_H5_WX = "H5-WX";
	String CLIENT_TYPE_H5_PC = "H5-PC";
	String CLIENT_TYPE_APP = "APP";

	/**
	 * 运费类型（0：包邮；1：固定邮费；2：运费模板）
	 */
	String FREIGHT_TYPE_0 = "0";

	/**
	 * 上下架
	 *  0. 下架  1.上架
	 */
	String UP = "1";

	/**
	 * 订单记录是否显示
	 */
	String  SHOW = "0";

	String YES = "1";
	String NO = "0";

	/**
	 * 0、未支付 1、已支付
	 */
	String PAY = "1";

}
