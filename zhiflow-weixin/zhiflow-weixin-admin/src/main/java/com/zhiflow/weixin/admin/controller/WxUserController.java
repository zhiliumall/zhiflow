/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.controller;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.zhiflow.common.core.util.R;
import com.zhiflow.api.weixin.dto.WxMpUserInsideDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.weixin.admin.config.mp.WxMpConfiguration;
import com.zhiflow.weixin.admin.service.WxAppService;
import com.zhiflow.weixin.admin.service.WxUserService;
import com.zhiflow.weixin.common.dto.LoginDTO;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import com.zhiflow.weixin.common.entity.WxApp;
import com.zhiflow.weixin.common.entity.WxUser;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.service.WxOAuth2Service;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 微信用户
 *
 * @date 2019-03-25 15:39:39
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/wxuser")
@Tag(description = "wxuser", name = "微信用户管理")
public class WxUserController {

	private final WxUserService wxUserService;
	private final WxAppService wxAppService;

	/**
	 * 小程序获取登录后的微信用户
	 * @param loginDTO
	 * @return R
	 */

	@Operation(summary = "公众号仅获取openId（用于支付，不创建wxUser）")
	@Inside
	@PostMapping("/inside/mp/code2openid")
	public R<String> code2OpenIdMp(@RequestBody LoginDTO loginDTO) {
		log.info("[code2OpenIdMp-weixin] 收到请求: appId={}, jsCode={}",
			loginDTO.getAppId(),
			loginDTO.getJsCode() != null ? loginDTO.getJsCode().substring(0, Math.min(10, loginDTO.getJsCode().length())) + "..." : null);
		WxApp wxApp = wxAppService.findByAppId(loginDTO.getAppId());
		if (wxApp == null) {
			log.warn("[code2OpenIdMp-weixin] 无此公众号: appId={}", loginDTO.getAppId());
			return R.failed("系统内无此公众号：" + loginDTO.getAppId());
		}
		log.info("[code2OpenIdMp-weixin] 找到wxApp: id={}", wxApp.getId());
		try {
			WxMpService wxMpService = WxMpConfiguration.getMpService(wxApp.getId());
			log.info("[code2OpenIdMp-weixin] WxMpService已创建, appId={}", wxMpService.getWxMpConfigStorage().getAppId());
			WxOAuth2AccessToken token;
			log.info("[code2OpenIdMp-weixin] 普通模式, 调用getAccessToken...");
			token = wxMpService.getOAuth2Service().getAccessToken(loginDTO.getJsCode());
			log.info("[code2OpenIdMp-weixin] 成功, openId={}", token.getOpenId());
			return R.ok(token.getOpenId());
		} catch (WxErrorException e) {
			log.error("[code2OpenIdMp-weixin] WxErrorException: errCode={}, msg={}", e.getError().getErrorCode(), e.getMessage(), e);
			return R.failed("获取openId失败: " + e.getMessage());
		} catch (Exception e) {
			log.error("[code2OpenIdMp-weixin] 其他异常: {}", e.getMessage(), e);
			return R.failed("获取openId失败: " + e.getMessage());
		}
	}

	/**
	 * 公众号用 OAuth code 换取 openId 并绑定到指定商城用户（创建或更新 wxUser）
	 */
	@Operation(summary = "公众号绑定openId到商城用户")
	@Inside
	@PostMapping("/inside/mp/bindOpenId")
	public R<WxUserInsideDTO> bindOpenIdMp(@RequestBody LoginDTO loginDTO) {
		WxApp wxApp = wxAppService.findByAppId(loginDTO.getAppId());
		if (wxApp == null) {
			return R.failed("系统内无此公众号：" + loginDTO.getAppId());
		}
		String mallUserId = loginDTO.getMallUserId();
		if (StrUtil.isBlank(mallUserId)) {
			return R.failed("缺少商城用户ID");
		}
		try {
			WxMpService wxMpService = WxMpConfiguration.getMpService(wxApp.getId());
			WxOAuth2AccessToken token;
			token = wxMpService.getOAuth2Service().getAccessToken(loginDTO.getJsCode());
			String openId = token.getOpenId();
			if (StrUtil.isBlank(openId)) {
				return R.failed("获取openId为空");
			}
			// 查找或创建 wxUser
			WxUser wxUser = wxUserService.getByOpenId(wxApp.getId(), openId);
			if (wxUser == null) {
				wxUser = new WxUser();
				wxUser.setOpenId(openId);
				wxUser.setAppId(wxApp.getId());
				wxUser.setAppType(wxApp.getAppType());
			}
			wxUser.setMallUserId(mallUserId);
			wxUser.setUnionId(token.getUnionId());
			wxUserService.saveOrUpdate(wxUser);
			return R.ok(WxInsideProjections.toDto(wxUser));
		} catch (WxErrorException e) {
			log.error("公众号绑定openId失败", e);
			return R.failed("绑定openId失败");
		}
	}

	/**
	 * 公众号登录获取WxMpUser
	 * @param loginDTO
	 * @return R
	 */
	@Operation(summary = "公众号登录获取WxMpUser")
	@Inside
	@PostMapping("/inside/mp/code2wxuser")
	public R<WxUserInsideDTO> code2WxUserMp(@RequestBody LoginDTO loginDTO){
		WxApp wxApp = wxAppService.findByAppId(loginDTO.getAppId());
		if(wxApp == null){
			return R.failed("系统内无此公众号：" + loginDTO.getAppId() + "；请在后台添加");
		}
		try {
			WxMpService wxMpService = WxMpConfiguration.getMpService(wxApp.getId());
			WxOAuth2Service wxOAuth2Service = wxMpService.getOAuth2Service();
			WxOAuth2AccessToken wxOAuth2AccessToken;
			wxOAuth2AccessToken = wxOAuth2Service.getAccessToken(loginDTO.getJsCode());
			String openId = wxOAuth2AccessToken.getOpenId();
			WxUser wxUser = wxUserService.getByOpenId(wxApp.getId(),openId);
			if(wxUser == null){
				wxUser = new WxUser();
				wxUser.setOpenId(openId);
				wxUser.setAppId(wxApp.getId());
			}
			if("snsapi_userinfo".equals(loginDTO.getScope())){
				WxOAuth2UserInfo wxMpUser = wxOAuth2Service.getUserInfo(wxOAuth2AccessToken,null);
				BeanUtil.copyProperties(wxMpUser,wxUser);
				wxUser.setHeadimgUrl(wxMpUser.getHeadImgUrl());
				wxUser.setNickName(wxMpUser.getNickname());
				wxUser.setUpdateTime(LocalDateTime.now());
//				wxUserService.saveOrUpdate(wxUser);
			}
			return R.ok(WxInsideProjections.toDto(wxUser));
		} catch (WxErrorException e) {
			log.error("Operation failed", e);
			return R.failed("公众号登录获取WxMpUser失败：" + loginDTO.getAppId());
		}
	}

	@Operation(summary = "获取公众号用户的信息")
	@Inside
	@PostMapping("/inside/mp/wxuser")
	public R<WxMpUserInsideDTO> wxUserMp(@RequestBody LoginDTO loginDTO){
		WxApp wxApp = wxAppService.findByAppId(loginDTO.getAppId());
		if(wxApp == null){
			return R.failed("系统内无此公众号：" + loginDTO.getAppId() + "；请在后台添加");
		}
		try {
			WxMpService wxMpService = WxMpConfiguration.getMpService(wxApp.getId());
			WxMpUser wxMpUser = wxMpService.getUserService().userInfo(loginDTO.getOpenId());
			return R.ok(WxInsideProjections.toDto(wxMpUser));
		} catch (WxErrorException e) {
			log.error("Operation failed", e);
			return R.failed("公众号登录获取WxMpUser失败：" + loginDTO.getAppId());
		}
	}

	/**
	 * 通过id查询微信用户
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询微信用户")
	@Inside
	@GetMapping("/inside/{id}")
	public R<WxUserInsideDTO> getByIdInside(@PathVariable("id") String id){
		return R.ok(WxInsideProjections.toDto(wxUserService.getById(id)));
	}

	/**
	 * 通过union_id查询绑定了商城的微信用户
	 * @param unionId
	 * @param wxUserId 过滤当前微信用户
	 * @return R
	 */
	@Operation(summary = "通过union_id查询绑定了商城的微信用户")
	@Inside
	@GetMapping("/inside/by/unionid/{unionId}/{wxUserId}")
	public R<List<WxUserInsideDTO>> getByUnionIdInside(@PathVariable("unionId") String unionId, @PathVariable("wxUserId") String wxUserId){
		List<WxUser> listWxUser = wxUserService.list(Wrappers.<WxUser>lambdaQuery()
				.eq(WxUser::getUnionId, unionId)
				.notIn(WxUser::getId,wxUserId)//过滤当前微信用户
				.isNotNull(WxUser::getMallUserId));//有绑定商城用户
		return R.ok(WxInsideProjections.toDtoList(listWxUser));
	}

	/**
	 * 通过union_id查询绑定了商城的微信用户
	 * @param unionId
	 * @param
	 * @return R
	 */
	@Operation(summary = "通过union_id查询绑定了商城的微信用户")
	@Inside
	@GetMapping("/inside/by/unionid/{unionId}")
	public R<WxUserInsideDTO> getByUnionIdInside(@PathVariable("unionId") String unionId){
		WxUser listWxUser = wxUserService.getOne(Wrappers.<WxUser>lambdaQuery()
				.eq(WxUser::getUnionId, unionId).last("limit 1"));
		return R.ok(WxInsideProjections.toDto(listWxUser));
	}

	/**
	 * 通过公众号授权更新微信用户
	 * @param wxOpenDataDTO
	 * @return R
	 */
	@Operation(summary = "通过公众号授权更新微信用户")
	@Inside
	@PutMapping("/mp/inside")
	public R<WxUserInsideDTO> updateByMpInside(@RequestBody WxOpenDataDTO wxOpenDataDTO){
		return R.ok(WxInsideProjections.toDto(wxUserService.updateByMpInside(wxOpenDataDTO)));
	}

	/**
	 * 保存微信用户
	 * @param wxUser
	 * @return R
	 */
	@Operation(summary = "保存微信用户")
	@Inside
	@PostMapping("/inside")
	public R<WxUserInsideDTO> saveOrUpdateInside(@RequestBody WxUserInsideDTO request){
		WxUser wxUser = WxInsideProjections.toEntity(request);
		try{
			wxUserService.saveOrUpdate(wxUser);
		}catch (DuplicateKeyException e){
			if(e.getMessage().contains("uk_appid_openid")){
				return R.ok(WxInsideProjections.toDto(
						wxUserService.getByOpenId(wxUser.getAppId(),wxUser.getOpenId())));
			}
		}

//		if("".equals(wxUser.getMallUserId()) && StrUtil.isNotBlank(wxUser.getId())){
//			if(StrUtil.isBlank(wxUser.getUnionId())){
//				//没有UnionId，直接解绑退出商城登录
//				wxUserService.update(
//						new UpdateWrapper<WxUser>().lambda()
//								.set(WxUser::getMallUserId,null)
//								.eq(WxUser::getId,wxUser.getId())
//				);
//			}else{
				//有UnionId，解绑该unionId的所有用户
//				wxUserService.update(
//						new UpdateWrapper<WxUser>().lambda()
//								.set(WxUser::getMallUserId,null)
//								.eq(WxUser::getUnionId,wxUser.getUnionId())
//				);
//			}
//		}
		return R.ok(WxInsideProjections.toDto(wxUser));
	}

	/**
	 * Real-time MP follow check used by policy guards. The binding is located only by
	 * the authenticated mall user and configured AppID; callers never provide an openId.
	 */

	@Operation(summary = "根据商城用户ID、appId获取微信用户")
	@Inside
	@GetMapping("/inside/{mallUserId}/{appId}")
	public R<WxUserInsideDTO> getByMallUserId(@PathVariable("mallUserId") String mallUserId, @PathVariable("appId") String appId){
		List<WxUser> wxUserList = wxUserService.list(Wrappers.<WxUser>query().lambda()
				.eq(WxUser::getMallUserId, mallUserId)
				.eq(WxUser::getAppId, appId)
				.last("LIMIT 1"));
		WxUser wxUser = wxUserList.isEmpty() ? null : wxUserList.getFirst();
		return R.ok(WxInsideProjections.toDto(wxUser));
	}

	/**
	 * 通过union_id查询绑定了商城的微信用户列表
	 * @param unionId
	 * @param
	 * @return R
	 */
	@Operation(summary = "通过union_id查询绑定了商城的微信用户列表")
	@Inside
	@GetMapping("/inside/by/unionid/list/{unionId}")
	public R<List<WxUserInsideDTO>> getListByUnionIdInside(@PathVariable("unionId") String unionId){
		List<WxUser> list = wxUserService.list(Wrappers.<WxUser>lambdaQuery()
				.eq(WxUser::getUnionId, unionId));
		return R.ok(WxInsideProjections.toDtoList(list));
	}

	/**
	 * 通过mall_user_id查询绑定了商城的微信用户列表
	 * @param userId
	 * @param
	 * @return R
	 */
	@Operation(summary = "通过mall_user_id查询绑定了商城的微信用户列表")
	@Inside
	@GetMapping("/inside/by/userid/list/{userId}")
	public R<List<WxUserInsideDTO>> getListByUserIdInside(@PathVariable("userId") String userId){
		List<WxUser> list = wxUserService.list(Wrappers.<WxUser>lambdaQuery()
				.eq(WxUser::getMallUserId, userId));
		return R.ok(WxInsideProjections.toDtoList(list));
	}
}
