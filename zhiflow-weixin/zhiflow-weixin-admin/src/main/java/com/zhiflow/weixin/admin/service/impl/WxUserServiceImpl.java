/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.weixin.admin.service.impl;

import cn.hutool.core.bean.BeanUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.google.common.collect.Lists;
import com.zhiflow.common.data.ddl.DdlExecutionRole;
import com.zhiflow.common.data.shardingtable.config.ShardingTableProperties;
import com.zhiflow.weixin.admin.service.WxAppService;
import com.zhiflow.weixin.admin.service.WxUserService;
import com.zhiflow.weixin.admin.mapper.WxUserMapper;
import com.zhiflow.weixin.admin.config.mp.WxMpConfiguration;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import com.zhiflow.weixin.common.entity.WxApp;
import com.zhiflow.weixin.common.entity.WxUser;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import me.chanjar.weixin.common.bean.WxOAuth2UserInfo;
import me.chanjar.weixin.common.bean.oauth2.WxOAuth2AccessToken;
import me.chanjar.weixin.common.error.WxErrorException;
import me.chanjar.weixin.common.service.WxOAuth2Service;
import me.chanjar.weixin.mp.api.WxMpService;
import me.chanjar.weixin.mp.api.WxMpUserService;
import me.chanjar.weixin.mp.bean.result.WxMpUser;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;

/**
 * 微信用户
 *
 * @date 2019-03-25 15:39:39
 */
@Slf4j
@Service
@AllArgsConstructor
public class WxUserServiceImpl extends ServiceImpl<WxUserMapper, WxUser> implements WxUserService {

	private final WxAppService wxAppService;
	private final ShardingTableProperties shardingTableProperties;
	/**
	 * DDL 执行身份（压测后 CARD-01）：开关打开后建租户分表前先 {@code SET LOCAL ROLE gp_migrator}，事务结束自动复原。
	 * 关时什么都不做。
	 */
	private final DdlExecutionRole ddlExecutionRole;

	@Override
	public WxUser getByOpenId(String appId, String openId){
		return this.baseMapper.selectOne(Wrappers.<WxUser>lambdaQuery()
				.eq(WxUser::getAppId, appId)
				.eq(WxUser::getOpenId,openId));
	}

	/**
	 * 分批次获取微信粉丝信息 每批100条
	 * @param wxMpUserService
	 * @param openidsList
	 * @return
	 * @throws WxErrorException
	 * @author
	 */
	private List<WxMpUser> getWxMpUserList(WxMpUserService wxMpUserService, List<String> openidsList) throws WxErrorException {
		// 粉丝openid数量
		int count = openidsList.size();
		if (count <= 0) {
			return new ArrayList<>();
		}
		List<WxMpUser> list = Lists.newArrayList();
		List<WxMpUser> followersInfoList;
		int a = count % 100 > 0 ? count / 100 + 1 : count / 100;
		for (int i = 0; i < a; i++) {
			if (i + 1 < a) {
				log.debug("i:{},from:{},to:{}", i, i * 100, (i + 1) * 100);
				followersInfoList = wxMpUserService.userInfoList(openidsList.subList(i * 100, ((i + 1) * 100)));
				if (null != followersInfoList && !followersInfoList.isEmpty()) {
					list.addAll(followersInfoList);
				}
			}
			else {
				log.debug("i:{},from:{},to:{}", i, i * 100, count - i * 100);
				followersInfoList = wxMpUserService.userInfoList(openidsList.subList(i * 100, count));
				if (null != followersInfoList && !followersInfoList.isEmpty()) {
					list.addAll(followersInfoList);
				}
			}
		}
		log.debug("本批次获取微信粉丝数：",list.size());
		return list;
	}

	@SneakyThrows
	@Override
	@Transactional(rollbackFor = Exception.class)
	public WxUser updateByMpInside(WxOpenDataDTO wxOpenDataDTO) {
		WxMpService wxMpService = WxMpConfiguration.getMpService(wxOpenDataDTO.getAppId());
		WxOAuth2Service wxOAuth2Service = wxMpService.getOAuth2Service();
		WxApp wxApp = wxAppService.findByAppId(wxOpenDataDTO.getAppId());
		WxOAuth2AccessToken wxOAuth2AccessToken;
		wxOAuth2AccessToken = wxOAuth2Service.getAccessToken(wxOpenDataDTO.getJsCode());

		WxOAuth2UserInfo wxMpUser = wxOAuth2Service.getUserInfo(wxOAuth2AccessToken,null);
		WxUser wxUser = new WxUser();
		BeanUtil.copyProperties(wxMpUser,wxUser);
		wxUser.setId(wxOpenDataDTO.getUserId());
		wxUser.setAppId(wxOpenDataDTO.getAppId());
		wxUser.setMallUserId(wxOpenDataDTO.getMallUserId());
		wxUser.setHeadimgUrl(wxMpUser.getHeadImgUrl());
		wxUser.setNickName(wxMpUser.getNickname());
		wxUser.setUpdateTime(LocalDateTime.now());
		baseMapper.updateById(wxUser);
		return baseMapper.selectById(wxUser.getId());
	}
}
