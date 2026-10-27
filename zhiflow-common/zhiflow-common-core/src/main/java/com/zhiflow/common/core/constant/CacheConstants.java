package com.zhiflow.common.core.constant;

/**
 * @author
 * <p>
 * 缓存的key 常量
 */
public interface CacheConstants {

	/**
	 * 公共缓存前缀标识
	 * RedisAutoCacheManager.getCache重写@Cacheable value 值，没有公共缓存前缀标识追加租户ID
	 */
	String PUBLIC_CACHE = "public:";
	/**
	 * 用户信息缓存
	 */
	String USER_CACHE = PUBLIC_CACHE + "user_cache";

	/**
	 * oauth 客户端信息
	 */
	String OAUTH_CLIENT_CACHE = PUBLIC_CACHE + "token::access_token";

	/**
	 * 菜单信息缓存
	 */
	String MENU_CACHE = PUBLIC_CACHE + "menu_cache";

	/**
	 * 字典缓存
	 */
	String DICT_CACHE = PUBLIC_CACHE + "dict_cache";

	/**
	 * 默认验证码前缀
	 */
	String VER_CODE_DEFAULT = PUBLIC_CACHE + "ver_code_default:";

	/**
	 * wxapp缓存
	 */
	String WXAPP_WEIXIN_SIGN_CACHE = PUBLIC_CACHE + "wx:weixin_Sign";
	/**
	 * wxapp缓存
	 */
	String WXAPP_APP_ID_CACHE = PUBLIC_CACHE + "wx:app_id";
	/**
	 * 小程序相关缓存
	 */
	String WXMA_CACHE= "wx:ma";
	/**
	 * 公众号相关缓存
	 */
	String WXMP_CACHE= "wx:mp";
	/**
	 * shopInfo店铺缓存
	 */
	String MALL_SHOP_INFO_CACHE = "mall:shop_info";
	/**
	 * 支付配置缓存
	 */
	String PAY_CONFIC_APP_ID_CACHE = "pay:config:appid";
	/**
	 * 支付配置缓存
	 */
	String PAY_CONFIC_TYPE_CACHE = "pay:config:type";
	/**
	 * 支付配置缓存（findByAppIdAndType）
	 */
	String PAY_CONFIC_APP_ID_TYPE_CACHE = "pay:config:appid_type";
	/**
	 * 商品详情静态快照（spu、在售 SKU、规格值、规格树；不含库存、销量、成本价），10 分钟。
	 * 后台改商品、SKU、规格后提交后按 spuId 清；下单、支付、取消都不清
	 */
	String MALL_GOODS_DETAIL_CACHE = "mall:goods_detail#600";
	/**
	 * 商品库存与销量（各在售 SKU 的库存 + 商品销量），5 秒。展示最多晚 5 秒，下单校验永远以数据库为准；
	 * 下单、支付、取消都不清（热门商品每 5 秒最多回源一次），后台改库存、SKU 后提交后清
	 */
	String MALL_GOODS_STOCK_CACHE = "mall:goods_stock#5";
	/**
	 * 商品品牌（按 id），30 分钟。商品详情与 /goodsbrand/{id} 共用；后台品牌改、删后提交后清
	 */
	String MALL_GOODS_BRAND_CACHE = "mall:goods_brand#1800";
	/**
	 * 首页轮播（启用的前 10 张，key 是无参的 SimpleKey []），60 秒；后台轮播增删改后提交后清
	 */
	String MALL_BANNER_CACHE = "mall:banner:enabled#60";
	/**
	 * 搜索页热搜词（前 10 个，key 是无参的 SimpleKey []），60 秒；后台热搜词增删改后提交后清
	 */
	String MALL_HOTWORD_CACHE = "mall:hotword:top#60";
	/**
	 * {@code @Cacheable(condition = ...)}：线程上拿不到租户时不走缓存。
	 * 否则定时任务、MQ 线程会写出不带租户前缀的键（见 RedisAutoCacheManager#getCache）
	 */
	String TENANT_PRESENT = "T(com.zhiflow.common.data.tenant.TenantContextHolder).getTenantId() != null";
	/**
	 * 商城商品分类树（启用的整棵树，key 固定为 tree），30 分钟。后台分类增删改后提交后清
	 */
	String MALL_GOODSCATEGORY_CACHE = "mall:goods_category#1800";
	/**
	 * 用户整份购物车（{@code /shoppingcart/page} 不带筛选参数时的结果快照，key 是 userId），10 分钟。
	 * 加购 / 改 / 删、下单删已购行后提交后清该用户；后台改商品、SKU、规格、店铺后，按购物车里有这些商品的用户逐个清。
	 * 库存、销量可滞后（下单、支付、取消不清），下单校验永远以数据库为准
	 */
	String MALL_SHOPPING_CART_CACHE = "mall:shopping_cart#600";
	/**
	 * redis订单key
	 */
	String MALL_ORDER_KEY_STATUS_2 = "mall:order:status_2";
	/**
	 * 前端用户3rd_session拼接前缀
	 */
	String THIRD_SESSION_BEGIN = "app:3rd_session";
	/**
	 * 前端用户3rd_session过期时间(单位：小时)
	 */
	long TIME_OUT_SESSION = 24 * 5;
	/**
	 * 存储配置缓存
	 */
	String SYS_CONFIG_STORAGE = "sys:config_storage";

}
