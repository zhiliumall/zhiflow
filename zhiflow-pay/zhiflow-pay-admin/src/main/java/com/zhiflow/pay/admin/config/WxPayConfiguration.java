/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.config;

import cn.hutool.core.util.StrUtil;
import com.github.binarywang.wxpay.config.WxPayConfig;
import com.github.binarywang.wxpay.service.WxPayService;
import com.github.binarywang.wxpay.service.impl.WxPayServiceImpl;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.pay.admin.service.PayConfigService;
import com.zhiflow.pay.common.constant.PayConstants;
import com.zhiflow.pay.common.entity.PayConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 微信支付Configuration
 *
 */
@Slf4j
@Configuration
public class WxPayConfiguration {

	/**
	 * 当前租户没有微信直连收款配置（{@code pay_config} type=1 不存在或已删）时的统一文案。
	 * {@code WxPayController} 各接口据此返回 {@code R.failed}，本类据此抛 {@link BizException}。
	 */
	public static final String NOT_CONFIGURED = "当前租户没有配置微信支付";

	private static PayConfigService payConfigService;

	/**
	 * 按「租户 + 服务商子维度 + 配置指纹」缓存的 WxPayService。
	 * sslContext/apiV3HttpClient/verifier 由 wxjava 懒加载并缓存在 WxPayConfig 实例上，
	 * 每请求 new 会重复解析商户私钥/证书、重建 v3 HttpClient；复用实例后只装配一次。
	 * 指纹自失效：admin 改配置 → findByType 缓存被 evict → 读到新值 → 指纹变 → 换 key 重建，
	 * 换证书文件（内容变路径不变）由文件 mtime/size 进指纹兜住；旧实例弃用等 GC，无需清理钩子。
	 * 本地 Map 不经过 RedisAutoCacheManager，租户前缀不会自动注入，tenantId 必须显式进 key。
	 */
	private static final Map<String, WxPayService> SERVICE_CACHE = new ConcurrentHashMap<>();

	private static final int CACHE_SIZE_WARN_THRESHOLD = 500;

	@Autowired
	public WxPayConfiguration(PayConfigService payConfigService) {
		WxPayConfiguration.payConfigService = payConfigService;
	}

	/**
	 *  获取WxPayService
	 * @param wxPayConfig wxPayConfig.appId、wxPayConfig.subAppId
	 * @return
	 */
	public static WxPayService getPayService(WxPayConfig wxPayConfig) {
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_1);
		if (payConfig == null) {
			// 渠道没配置是业务失败：gRPC 下 FAILED_PRECONDITION + 原文案（调用方还原成 R.failed），
			// REST 下 200 + R.failed。裸 RuntimeException 会变成 INTERNAL「服务内部错误」（2026-09-29 生产实测）。
			throw new BizException(NOT_CONFIGURED);
		}
		// 普通商户模式下所有资金调用点最终都以 sub 字段为空发起（unifiedOrder/refundOrder/queryOrder 均置空），
		// 构建前统一归一化：缓存实例即调用方置空后的终态，等价原「拿到 service 后再 setSubMchId(null)」
		boolean normalMerchantModel = PayConstants.PAY_MODEL_2.equals(payConfig.getModel());
		String subAppId = normalMerchantModel ? null : wxPayConfig.getSubAppId();
		String subMchId = normalMerchantModel ? null : wxPayConfig.getSubMchId();
		String tenantId = TenantContextHolder.getTenantId();
		if (StrUtil.isBlank(tenantId)) {
			// 无租户上下文时 findByType 本会 fail loud 查不到配置，此处兜底：不落共享缓存，保持原每次新建
			log.warn("getPayService缺少租户上下文，跳过WxPayService缓存");
			return buildPayService(payConfig, subAppId, subMchId);
		}
		String cacheKey = tenantId + ':' + PayConstants.PAY_TYPE_1 + ':' + subAppId + ':' + subMchId
				+ ':' + fingerprint(payConfig);
		if (SERVICE_CACHE.size() > CACHE_SIZE_WARN_THRESHOLD) {
			log.warn("WxPayService缓存条目数{}超过{}，请检查pay_config指纹字段是否异常波动", SERVICE_CACHE.size(), CACHE_SIZE_WARN_THRESHOLD);
		}
		// computeIfAbsent 保证同 key 只构造一次，构造抛错不会把坏实例留在缓存
		return SERVICE_CACHE.computeIfAbsent(cacheKey, key -> buildPayService(payConfig, subAppId, subMchId));
	}

	private static WxPayService buildPayService(PayConfig payConfig, String subAppId, String subMchId) {
		WxPayConfig wxPayConfig = new WxPayConfig();
		wxPayConfig.setSubAppId(subAppId);
		wxPayConfig.setSubMchId(subMchId);
		wxPayConfig.setAppId(payConfig.getAppId());
		wxPayConfig.setMchId(payConfig.getMchId());
		wxPayConfig.setMchKey(payConfig.getMchKey());
		wxPayConfig.setKeyPath(payConfig.getKeyPath());
		wxPayConfig.setPrivateKeyPath(payConfig.getPrivateKeyPath());
		wxPayConfig.setPrivateCertPath(payConfig.getPrivateCertPath());
		wxPayConfig.setCertSerialNo(payConfig.getCertSerialNo());
		wxPayConfig.setApiV3Key(payConfig.getApiV3Key());
		// 可以指定是否使用沙箱环境
		wxPayConfig.setUseSandboxEnv(false);
		// 显式固化超时（sdk 默认 connect 5s/read 10s）：连接收紧到 3s，支付网关抖动时尽快释放请求线程。
		// 支付宝侧无需处理：EasySDK 生成端硬编码 connect/read 均 15s（已核实字节码），有界。
		wxPayConfig.setHttpConnectionTimeout(3000);
		wxPayConfig.setHttpTimeout(10000);
		WxPayService wxPayService = new WxPayServiceImpl();
		wxPayService.setConfig(wxPayConfig);
		return wxPayService;
	}

	/**
	 * 指纹字段 = buildPayService 实际读取的全部 pay_config 字段 + model（决定 sub 字段归一化）+ 证书文件 stat
	 */
	private static String fingerprint(PayConfig payConfig) {
		return PayConfigFingerprint.of(
				payConfig.getId(),
				payConfig.getModel(),
				payConfig.getAppId(),
				payConfig.getMchId(),
				payConfig.getMchKey(),
				payConfig.getKeyPath(),
				payConfig.getPrivateKeyPath(),
				payConfig.getPrivateCertPath(),
				payConfig.getCertSerialNo(),
				payConfig.getApiV3Key(),
				PayConfigFingerprint.fileStat(payConfig.getKeyPath()),
				PayConfigFingerprint.fileStat(payConfig.getPrivateKeyPath()),
				PayConfigFingerprint.fileStat(payConfig.getPrivateCertPath()));
	}
}
