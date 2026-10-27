package com.zhiflow.common.core.constant;

/**
 * @author
 */
public interface CommonConstants {
	/**
	 * 是
	 */
	String YES = "1";
	/**
	 * 否
	 */
	String NO = "0";
	/**
	 * header 中租户ID
	 */
	String TENANT_ID = "tenant-id";
	/**
	 * header 中Authorization
	 */
	String AUTHORIZATION = "Authorization";
	/**
	 * 删除
	 */
	String STATUS_DEL = "1";
	/**
	 * 正常
	 */
	String STATUS_NORMAL = "0";

	/**
	 * 锁定
	 */
	String STATUS_LOCK = "9";
	/**
	 * 日志类型：正常操作日志
	 */
	String LOG_TYPE_0 = "0";
	/**
	 * 日志类型：异常操作日志
	 */
	String LOG_TYPE_9 = "9";
	/**
	 * 菜单
	 */
	String MENU = "0";

	/**
	 * 编码
	 */
	String UTF8 = "UTF-8";

	/**
	 * 邮箱
	 */
	String EMAIL = "email";

	/**
	 * 成功标记
	 */
	Integer SUCCESS = 0;
	/**
	 * 失败标记
	 */
	Integer FAIL = 1;

	/**
	 * 树形父类ID
	 */
	String PARENT_ID = "0";

	/**
	 * 租户类型 1：租户；-1：系统平台管理
	 */
	String TENANT_TYPE_1 = "1";
	/**
	 * 系统管理员租户ID
	 */
	String SYSTEM_TENANT_ID = "-1";

	/**
	 * 默认租户（单店铺商城只有这一个业务租户）。租户解析固定为它，不读请求头和域名；
	 * 只有系统租户 {@link #SYSTEM_TENANT_ID} 的超管令牌例外。
	 */
	String DEFAULT_TENANT_ID = "1";

	/**
	 * 默认店铺 ID：开源版单店铺，基线 shop_info 写这一行（enable='1'）。
	 * 商品保存、导入、加购、下单时 shop_id 为空一律取它，不要求前端传。
	 */
	String DEFAULT_SHOP_ID = "1";

	/**
	 * 租户管理员角色编码
	 */
	String ROLE_CODE_ADMIN = "ROLE_ADMIN";

	/**
	 * 文件类型：image：图片
	 */
	String FILE_TYPE_IMG = "image";

	String PHONE_CODE_2 = "2";
	String PHONE_CODE_3 = "3";

	/**
	 * 账号类型：-1、系统管理员；1、商城管理员
	 */
	String USER_TYPE_S = "-1";
	String USER_TYPE_1 = "1";

	/**
	 * JSON 资源
	 */
	String CONTENT_TYPE = "application/json; charset=utf-8";

	/**
	 * 网关注入的客户端 IP 地址
	 */
	String CLIENT_IP = "X-Client-IP";
	/**
	 * 网关注入的客户端省份
	 */
	String CLIENT_PROVINCE = "X-Client-Province";
	/**
	 * 网关注入的客户端城市
	 */
	String CLIENT_CITY = "X-Client-City";
	/**
	 * 网关注入的客户端区县
	 */
	String CLIENT_DISTRICT = "X-Client-District";
	/**
	 * 网关注入的客户端经度
	 */
	String CLIENT_LNG = "X-Client-Lng";
	/**
	 * 网关注入的客户端纬度
	 */
	String CLIENT_LAT = "X-Client-Lat";
	/**
	 * 网关注入的客户端 ISP
	 */
	String CLIENT_ISP = "X-Client-ISP";
	/**
	 * 网关注入的客户端区域编码
	 */
	String CLIENT_AREA_CODE = "X-Client-AreaCode";
}
