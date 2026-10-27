/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.mall.api.service.impl;

import cn.hutool.core.collection.CollectionUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.zhiflow.api.weixin.WxAppApi;
import com.zhiflow.api.weixin.WxUserApi;
import com.zhiflow.api.weixin.dto.WxAppInsideDTO;
import com.zhiflow.api.weixin.dto.WxUserInsideDTO;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.IpUtils;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.lock.annotation.DistributedLock;
import com.zhiflow.common.lock.enums.LockTimeoutStrategy;
import com.zhiflow.common.portal.constant.PortalConstants;
import com.zhiflow.common.portal.entity.ThirdSession;
import com.zhiflow.common.portal.interceptor.ThirdSessionInterceptor;
import com.zhiflow.common.portal.util.ApiUtil;
import com.zhiflow.common.portal.util.ThirdSessionHolder;
import com.zhiflow.mall.api.mapper.UserInfoMapper;
import com.zhiflow.mall.api.service.UserInfoService;
import com.zhiflow.mall.common.config.MallConfigProperties;
import com.zhiflow.mall.common.entity.UserInfo;
import com.zhiflow.weixin.common.dto.LoginDTO;
import com.zhiflow.weixin.common.dto.WxOpenDataDTO;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 商城用户（会员账号）。
 *
 * @date 2019-12-04 11:09:55
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserInfoServiceImpl extends ServiceImpl<UserInfoMapper, UserInfo> implements UserInfoService {

    private static final PasswordEncoder ENCODER = new BCryptPasswordEncoder();

    private final WxUserApi wxUserHttpClient;
    private final WxAppApi wxAppHttpClient;
    private final RedisTemplate<String, Object> redisTemplate;
    private final MallConfigProperties mallConfigProperties;

    @Override
    @DistributedLock(name = "UserInfo", keys = {"#entity.getId()"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
    public boolean updateById(UserInfo entity) {
        return super.updateById(entity);
    }

    @Override
    @DistributedLock(name = "UserInfo", keys = {"#id"}, lockTimeoutStrategy = LockTimeoutStrategy.FAIL_FAST)
    public boolean removeById(Serializable id) {
        return super.removeById(id);
    }

    // ────────────────────────────── 微信网页授权 ──────────────────────────────

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfo loginMp(LoginDTO loginDTO, HttpServletRequest request) {
        R<WxAppInsideDTO> r = wxAppHttpClient.getById(loginDTO.getAppId(), SecurityConstants.FROM_IN);
        WxAppInsideDTO wxApp = r == null ? null : r.getData();
        if (wxApp == null) {
            throw new BizException("未配置公众号：" + loginDTO.getAppId() + "，请在后台「微信网页授权配置」里添加");
        }
        // 通过 code 换微信用户（网页授权）
        R<WxUserInsideDTO> r1 = wxUserHttpClient.code2WxUserMp(loginDTO, SecurityConstants.FROM_IN);
        if (r1 == null || !r1.isOk() || r1.getData() == null) {
            throw new BizException(r1 == null ? "微信授权失败" : r1.getMsg());
        }
        WxUserInsideDTO wxUser = r1.getData();
        if (StrUtil.isBlank(wxUser.getId())) {
            wxUser.setAppId(wxApp.getId());
            wxUser.setAppType(wxApp.getAppType());
        }
        wxUser = saveWxUser(wxUser);

        UserInfo userInfo;
        if (StrUtil.isNotBlank(wxUser.getMallUserId())) {
            userInfo = getById(wxUser.getMallUserId());
            if (userInfo == null) {
                throw new BizException("该微信账号绑定的商城账号已不存在");
            }
        }
        else {
            // 首次授权：建一个没有手机号的商城用户并绑定
            UserInfo created = new UserInfo();
            created.setAppType(PortalConstants.CLIENT_TYPE_H5_WX);
            created.setAppId(wxApp.getId());
            created.setNickName(wxUser.getNickName());
            created.setHeadimgUrl(wxUser.getHeadimgUrl());
            userInfo = saveNewUser(created, request);
            wxUser.setMallUserId(userInfo.getId());
            saveWxUser(wxUser);
        }
        ThirdSession thirdSession = new ThirdSession();
        thirdSession.setTenantId(TenantContextHolder.getTenantId());
        thirdSession.setAppId(wxApp.getId());
        thirdSession.setWxUserId(wxUser.getId());
        thirdSession.setOpenId(wxUser.getOpenId());
        thirdSession.setUserId(userInfo.getId());
        String thirdSessionKey = "wx:" + wxUser.getId() + ":" + UUID.randomUUID();
        storeSession(ThirdSessionInterceptor.getSessionRedisKey(thirdSessionKey), thirdSession);
        userInfo.setThirdSession(thirdSessionKey);
        return userInfo;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfo updateByMp(WxOpenDataDTO wxOpenDataDTO) {
        R<WxUserInsideDTO> r = wxUserHttpClient.updateByMpInside(wxOpenDataDTO, SecurityConstants.FROM_IN);
        if (r == null || !r.isOk()) {
            throw new BizException(r == null ? "更新微信资料失败" : r.getMsg());
        }
        WxUserInsideDTO wxUser = r.getData();
        if (wxUser == null || StrUtil.isBlank(wxUser.getMallUserId())) {
            return null;
        }
        UserInfo userInfo = getById(wxUser.getMallUserId());
        if (userInfo != null) {
            userInfo.setNickName(wxUser.getNickName());
            userInfo.setHeadimgUrl(wxUser.getHeadimgUrl());
            this.updateById(userInfo);
        }
        return userInfo;
    }

    // ────────────────────────────── 账号 ──────────────────────────────

    @Override
    public UserInfo findByAccount(String account) {
        if (StrUtil.isBlank(account)) {
            return null;
        }
        String value = account.trim();
        return getOne(Wrappers.<UserInfo>lambdaQuery()
                .and(w -> w.eq(UserInfo::getUsername, value.toLowerCase()).or().eq(UserInfo::getPhone, value))
                .last("limit 1"));
    }

    @Override
    public UserInfo findByPhone(String phone) {
        if (StrUtil.isBlank(phone)) {
            return null;
        }
        return getOne(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getPhone, phone.trim()).last("limit 1"));
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public UserInfo register(String username, String phone, String password, HttpServletRequest request) {
        String normalized = StrUtil.isBlank(username) ? null : username.trim().toLowerCase();
        if (normalized == null && StrUtil.isBlank(phone)) {
            throw new BizException("请填写用户名或手机号");
        }
        if (normalized != null && count(Wrappers.<UserInfo>lambdaQuery().eq(UserInfo::getUsername, normalized)) > 0) {
            throw new BizException("用户名已被使用");
        }
        if (StrUtil.isNotBlank(phone) && findByPhone(phone) != null) {
            throw new BizException("该手机号已注册，请直接登录");
        }
        UserInfo userInfo = new UserInfo();
        userInfo.setUsername(normalized);
        userInfo.setPhone(StrUtil.blankToDefault(phone, null));
        userInfo.setPassword(StrUtil.isBlank(password) ? null : ENCODER.encode(password));
        userInfo.setAppType(ApiUtil.getClientType(request));
        userInfo.setAppId(ApiUtil.getAppId(request));
        return saveNewUser(userInfo, request);
    }

    /** 新用户落库：补默认头像、昵称与用户编码，记注册 IP 与设备。 */
    private UserInfo saveNewUser(UserInfo userInfo, HttpServletRequest request) {
        if (StrUtil.isBlank(userInfo.getAppType())) {
            userInfo.setAppType(PortalConstants.CLIENT_TYPE_H5);
        }
        if (StrUtil.isBlank(userInfo.getHeadimgUrl())) {
            userInfo.setHeadimgUrl(mallConfigProperties.getUserDefaultAvatar());
        }
        if (StrUtil.isBlank(userInfo.getNickName())) {
            userInfo.setNickName(StrUtil.isNotBlank(userInfo.getUsername()) ? userInfo.getUsername()
                    : StrUtil.isNotBlank(userInfo.getPhone())
                            ? userInfo.getPhone().replaceAll("(\\d{3})\\d{4}(\\d{4})", "$1****$2") : "微信用户");
        }
        if (userInfo.getUserCode() == null) {
            userInfo.setUserCode(baseMapper.selectNextUserCode());
        }
        if (request != null) {
            String ip = IpUtils.getIpAddr(request);
            userInfo.setRegisterIp(ip);
            userInfo.setLastLoginIp(ip);
            userInfo.setLastLoginTime2(LocalDateTime.now());
        }
        this.save(userInfo);
        UserInfo saved = getById(userInfo.getId());
        saved.setNewUser(true);
        return saved;
    }

    @Override
    public UserInfo openSession(UserInfo userInfo, HttpServletRequest request) {
        String clientType = ApiUtil.getClientType(request);
        String appId = ApiUtil.getAppId(request);
        ThirdSession current = ThirdSessionHolder.getThirdSession();
        String header = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
        String currentKey = StrUtil.isNotBlank(header) ? ThirdSessionInterceptor.getSessionRedisKey(header) : null;
        // 微信内（已网页授权、会话里有微信身份）：把微信用户绑到这个商城账号，复用当前会话，JSAPI 支付才有 openid
        if (current != null && currentKey != null && StrUtil.isNotBlank(current.getWxUserId())
                && PortalConstants.CLIENT_TYPE_H5_WX.equals(clientType) && StrUtil.isNotBlank(appId)) {
            WxUserInsideDTO wxUser = wxUserOrFail(current.getWxUserId());
            if (wxUser != null) {
                wxUser.setMallUserId(userInfo.getId());
                if (StrUtil.isNotBlank(userInfo.getPhone())) {
                    wxUser.setPhone(userInfo.getPhone());
                }
                saveWxUser(wxUser);
                current.setUserId(userInfo.getId());
                current.setOpenId(wxUser.getOpenId());
                storeSession(currentKey, current);
                clearOtherWxSessions(wxUser, currentKey);
                userInfo.setThirdSession(header);
                recordLogin(userInfo.getId(), request);
                return userInfo;
            }
        }
        String thirdSessionKey = UUID.randomUUID().toString();
        ThirdSession thirdSession = new ThirdSession();
        thirdSession.setTenantId(userInfo.getTenantId());
        thirdSession.setUserId(userInfo.getId());
        storeSession(ThirdSessionInterceptor.getSessionRedisKey(thirdSessionKey), thirdSession);
        userInfo.setThirdSession(thirdSessionKey);
        recordLogin(userInfo.getId(), request);
        return userInfo;
    }

    @Override
    public void logout(HttpServletRequest request) {
        ThirdSession thirdSession = ThirdSessionHolder.getThirdSession();
        String header = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
        if (thirdSession == null || StrUtil.isBlank(header)) {
            return;
        }
        String key = ThirdSessionInterceptor.getSessionRedisKey(header);
        if (StrUtil.isNotBlank(thirdSession.getWxUserId())) {
            // 微信内：保留微信身份（下次免授权取 openid），只摘掉商城用户
            thirdSession.setUserId(null);
            storeSession(key, thirdSession);
        }
        else {
            redisTemplate.delete(key);
        }
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void terminate(HttpServletRequest request) {
        ThirdSession thirdSession = ThirdSessionHolder.getThirdSession();
        if (thirdSession == null || StrUtil.isBlank(thirdSession.getUserId())) {
            throw new BizException("请先登录");
        }
        String userId = thirdSession.getUserId();
        // 注销墓碑走 wrapper 显式 set：BaseEntity.delFlag 带 @TableLogic，updateById 会把它从 SET 子句里剥掉
        boolean ok = this.update(null, Wrappers.<UserInfo>lambdaUpdate()
                .eq(UserInfo::getId, userId)
                .set(UserInfo::getPhone, null)
                .set(UserInfo::getUsername, null)
                .set(UserInfo::getPassword, null)
                .set(UserInfo::getNickName, "已注销用户")
                .set(UserInfo::getDelFlag, "1")
                .set(UserInfo::getUpdateTime, LocalDateTime.now()));
        if (!ok) {
            throw new BizException("注销账号失败");
        }
        // 解绑微信用户：查询失败不能当成「没有绑定」，否则微信再登录会回到这个已注销的号上
        R<List<WxUserInsideDTO>> bound = wxUserHttpClient.getListByUserIdInside(userId, SecurityConstants.FROM_IN);
        if (bound == null || !bound.isOk()) {
            throw new BizException("注销账号失败，请稍后重试");
        }
        if (CollectionUtil.isNotEmpty(bound.getData())) {
            for (WxUserInsideDTO wxUser : bound.getData()) {
                wxUser.setMallUserId("");
                saveWxUser(wxUser);
                clearOtherWxSessions(wxUser, null);
            }
        }
        String header = request.getHeader(PortalConstants.HEADER_THIRDSESSION);
        if (StrUtil.isNotBlank(header)) {
            redisTemplate.delete(ThirdSessionInterceptor.getSessionRedisKey(header));
        }
    }

    @Override
    public void bindPhone(String userId, String phone) {
        UserInfo patch = new UserInfo();
        patch.setId(userId);
        patch.setPhone(phone);
        this.updateById(patch);
    }

    // ────────────────────────────── 内部 ──────────────────────────────

    private void storeSession(String key, ThirdSession session) {
        redisTemplate.opsForValue().set(key, JSONUtil.toJsonStr(session), CacheConstants.TIME_OUT_SESSION, TimeUnit.HOURS);
    }

    /** 清掉同一微信用户在其它地方的会话（换账号登录 / 注销后旧会话不能继续指向原商城用户）。 */
    private void clearOtherWxSessions(WxUserInsideDTO wxUser, String keepKey) {
        Set<String> keys = redisTemplate.keys(ThirdSessionInterceptor.getSessionRedisKey("") + "wx:" + wxUser.getId() + ":*");
        if (keys == null) {
            return;
        }
        if (keepKey != null) {
            keys.remove(keepKey);
        }
        if (!keys.isEmpty()) {
            redisTemplate.delete(keys);
        }
    }

    private void recordLogin(String userId, HttpServletRequest request) {
        try {
            UserInfo patch = new UserInfo();
            patch.setId(userId);
            patch.setLastLoginIp(IpUtils.getIpAddr(request));
            patch.setLastLoginTime2(LocalDateTime.now());
            this.updateById(patch);
        }
        catch (Exception e) {
            log.warn("更新登录信息失败 userId={}", userId, e);
        }
    }

    private WxUserInsideDTO saveWxUser(WxUserInsideDTO wxUser) {
        R<WxUserInsideDTO> r = wxUserHttpClient.saveOrUpdate(wxUser, SecurityConstants.FROM_IN);
        if (r == null || !r.isOk() || r.getData() == null) {
            throw new BizException(r == null ? "保存微信用户失败" : r.getMsg());
        }
        return r.getData();
    }

    /** 查询失败必须让本次操作失败重试，不能当成「没有微信用户」退化成不带 openid 的会话。 */
    private WxUserInsideDTO wxUserOrFail(String wxUserId) {
        R<WxUserInsideDTO> r = wxUserHttpClient.getById(wxUserId, SecurityConstants.FROM_IN);
        if (r == null || !r.isOk()) {
            throw new BizException("微信用户信息暂时获取失败，请稍后重试");
        }
        return r.getData();
    }
}
