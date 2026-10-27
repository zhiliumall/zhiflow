package com.zhiflow.api.weixin;

import com.zhiflow.api.weixin.dto.WxMpUserInsideDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.weixin.common.dto.LoginDTO;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 微信用户服务 API 接口定义
 *
 * <p>[B7] 2026-09-14 契约治理：14 个方法原先返回持久化实体 {@code WxUser}、
 * {@code wxUserMp} / {@code getPhoneNumber} 返回微信 SDK 类型、
 * {@code createTenantDataBaseTables} 是裸 {@code R}。现在统一走投影
 * {@link WxUserInsideDTO} / {@link WxMpUserInsideDTO} / {@link WxMaPhoneInsideDTO} 与 {@code R<Void>}。
 * 字段取舍的证据写在各投影类的 javadoc 里。
 *
 */
@HttpExchange(url = "/weixin", accept = "application/json", contentType = "application/json")
public interface WxUserApi {

	/**
	 * 公众号获取登录后的微信用户
	 */
	@PostExchange("/wxuser/inside/mp/code2wxuser")
	R<WxUserInsideDTO> code2WxUserMp(@RequestBody LoginDTO loginDTO,
						   @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 公众号仅获取openId（不创建/更新wxUser，仅用于支付）
	 */
	@PostExchange("/wxuser/inside/mp/code2openid")
	R<String> code2OpenIdMp(@RequestBody LoginDTO loginDTO,
							@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 公众号用 OAuth code 换取 openId 并绑定到指定商城用户
	 */
	@PostExchange("/wxuser/inside/mp/bindOpenId")
	R<WxUserInsideDTO> bindOpenIdMp(@RequestBody LoginDTO loginDTO,
						   @RequestHeader(SecurityConstants.FROM) String from);

	@PostExchange("/wxuser/inside/mp/wxuser")
	R<WxMpUserInsideDTO> wxUserMp(@RequestBody LoginDTO loginDTO,
						@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 获取微信用户信息
	 */
	@GetExchange("/wxuser/inside/{id}")
	R<WxUserInsideDTO> getById(@PathVariable("id") String id,
					 @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过union_id查询绑定了商城的微信用户
	 */
	@GetExchange("/wxuser/inside/by/unionid/{unionId}/{wxUserId}")
	R<List<WxUserInsideDTO>> getByUnionIdInside(@PathVariable("unionId") String unionId,
									   @PathVariable("wxUserId") String wxUserId,
									   @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过union_id查询一条微信用户。
	 *
	 * <p>⚠️ 2026-09-14 全仓 grep（{@code src/main} + {@code src/test}）：<b>零调用方</b>
	 * —— 同一次搜索命中了 2 参重载的真实调用（{@code UserInfoServiceImpl#wxUserRedisHandle}），
	 * 证明搜索模式本身在工作。本批仍按「不在迁移里夹删除」照常迁移，
	 * 「零调用边直接删」的裁决留给主会话。
	 */
	@GetExchange("/wxuser/inside/by/unionid/{unionId}")
	R<WxUserInsideDTO> getByUnionIdInside(@PathVariable("unionId") String unionId,
								 @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过微信公众号网页授权更新用户信息
	 */
	@PutExchange("/wxuser/mp/inside")
	R<WxUserInsideDTO> updateByMpInside(@RequestBody WxOpenDataDTO wxOpenDataDTO,
							   @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 新增或更新微信用户。
	 *
	 * <p>请求体与响应共用同一个投影：调用方拿到的对象改几个字段就整个传回来，
	 * 两个方向字段集合不一致会让「读得到但传不回去」的字段在下一次写入时行为不可预测。
	 */
	@PostExchange("/wxuser/inside")
	R<WxUserInsideDTO> saveOrUpdate(@RequestBody WxUserInsideDTO wxUser,
						  @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 根据商城用户ID、appId获取微信用户
	 */
	@GetExchange("/wxuser/inside/{mallUserId}/{appId}")
	R<WxUserInsideDTO> getByMallUserId(@PathVariable("mallUserId") String mallUserId,
							 @PathVariable("appId") String appId,
							 @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过union_id查询绑定了商城的微信用户列表
	 */
	@GetExchange("/wxuser/inside/by/unionid/list/{unionId}")
	R<List<WxUserInsideDTO>> getListByUnionIdInside(@PathVariable("unionId") String unionId,
										   @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过userId查询绑定了商城的微信用户列表
	 */
	@GetExchange("/wxuser/inside/by/userid/list/{userId}")
	R<List<WxUserInsideDTO>> getListByUserIdInside(@PathVariable("userId") String userId,
										  @RequestHeader(SecurityConstants.FROM) String from);
}
