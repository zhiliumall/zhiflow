package com.zhiflow.mall.common.constant;

/**
 * 全局返回码
 * 2019年7月25日
 */
public enum MallReturnCode {

	ERR_70001(70001, "该状态订单不允许操作"){},
	ERR_70002(70002, "请选择付款方式"){},
	ERR_70003(70003, "没有符合下单条件的规格商品，该商品可能已下架或库存不足"){},
	ERR_70004(70004, "只有未支付的详单能发起支付"){},
	ERR_70005(70005, "无效订单"){},
	ERR_70006(70006, "订单正在处理中,暂不能操作"){},
	/** 下单风控：同一用户 / IP 短时间下单过多，需先过验证码再带 X-Order-Captcha 头重提交（OrderCaptchaGuard）；响应 data.captchaRequired=true */
	ERR_70007(70007, "请完成安全验证后再提交"){},
	/** 下单扣库存等热点商品行锁超过上限（zhiflow.perf.order-lock-timeout.*）或撞死锁：整单已回滚（OrderStockDeduction）；或开事务前没领到热点 SKU 在途名额（zhiflow.perf.hot-sku-limiter.*，HotSkuOrderLimiter）。防重 token 已释放，可直接重提 */
	ERR_70008(70008, "商品太火爆，请稍后再试"){},
	/** 微信内（H5-WX）发起微信支付却拿不到 openid：前端据此走一次网页授权换 openid 再重试 */
	ERR_70009(70009, "请先完成微信授权再支付"){},
	ERR_80001(80001, "你已领取过该优惠券"){},
	ERR_80002(80002, "无此电子券"){},
	ERR_80003(80003, "电子券库存不足"){},
	ERR_80004(80004, "该商品不存在或已删除"){},
	ERR_80005(80005, "砍价ID不能为空"){},
	ERR_80006(80006, "该砍价已经购买，请勿重复购买"){},
	ERR_80007(80007, "不能自己重复砍价"){},
	ERR_80008(80008, "砍价未砍到底价，暂不能以底价下单"){},
	ERR_80010(80010, "无效的拼团活动，此拼团可能已过期或已关闭"){},
	ERR_80011(80011, "该拼团已经拼满，请重开新团"){},
	ERR_80012(80012, "你已是该团成员，请重开新团"){},
	ERR_80020(80020, "该活动已有用户参与，不能删除"){},
	ERR_80030(80030, "你已超过该秒杀限购数量"){},
	ERR_80031(80031, "该秒杀商品已售罄"){},
	ERR_80032(80032, "无效的秒杀活动"){},
	ERR_80033(80033, "该秒杀活动不在有效时间内"){},
	ERR_80047(80047, "该活动不在有效时间内"){},
	ERR_80044(80044, "该店铺不存在或已删除"){},
	ERR_80045(80045, "购物车已满，先清理下吧"){},
	ERR_80046(80046, "该秒杀不存在或已删除"){},
	ERR_80060(80060, "客服评分参数异常"){},
	ERR_80061(80061, "客服评分已提交"){},
	ERR_80062(80062, "客服评分无效"){},
		BARGAIN_FOLLOW_BIND_REQUIRED(80063, "尚未绑定当前活动公众号，无法验证关注状态"){},
		BARGAIN_FOLLOW_REQUIRED(80064, "请先关注活动公众号后重新验证"){},
		BARGAIN_FOLLOW_CHECK_UNAVAILABLE(80065, "暂无法验证关注状态，请稍后重试"){},
	ERR_80070(80070, "该文章不存在或已删除"){},
	;

	MallReturnCode(int code, String msg) {
		this.code = code;
		this.msg = msg;
	}

	private int code;
	private String msg;

	public int getCode() {
		return code;
	}
	public void setCode(int code) {
		this.code = code;
	}
	public String getMsg() {
		return msg;
	}
	public void setMsg(String msg) {
		this.msg = msg;
	}

	@Override
	public String toString() {
		return "MyReturnCode{" + "code='" + code + '\'' + "msg='" + msg + '\'' + '}';
	}

}
