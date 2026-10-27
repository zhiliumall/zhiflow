/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.config;

import cn.hutool.core.util.StrUtil;
import com.alipay.easysdk.factory.MultipleFactory;
import com.alipay.easysdk.kernel.Config;
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
public class AliPayConfiguration {

	/**
	 * 当前租户没有支付宝配置（{@code pay_config} type=2 不存在或已删）时的统一文案。
	 * {@code AliPayController} 各接口据此返回 {@code R.failed}，本类据此抛 {@link BizException}。
	 */
	public static final String NOT_CONFIGURED = "当前租户没有配置支付宝";

	private static PayConfigService payConfigService;

	/**
	 * 按「租户 + 配置指纹」缓存的 MultipleFactory。
	 * setOptions 构造 Context 时会同步读取并解析三份证书文件（应用/支付宝/根证书），每请求 new 每次重解析；
	 * Context 构造完成后只读，工厂各方法每次新建 Client 包装同一 Context，可安全并发复用。
	 * 指纹自失效与租户维度说明同 WxPayConfiguration.SERVICE_CACHE 注释。
	 */
	private static final Map<String, MultipleFactory> FACTORY_CACHE = new ConcurrentHashMap<>();

	private static final int CACHE_SIZE_WARN_THRESHOLD = 500;

	@Autowired
	public AliPayConfiguration(PayConfigService payConfigService) {
		AliPayConfiguration.payConfigService = payConfigService;
	}

	/**
	 * 获取Factory
	 * @return
	 */
	public static MultipleFactory getFactory() throws Exception {
		PayConfig payConfig = payConfigService.findByType(PayConstants.PAY_TYPE_2);
		if(payConfig == null){
			// 业务失败而不是受检 Exception：后者会被 AliPayController#wrapChecked 包成 IllegalStateException，
			// gRPC 下变成 INTERNAL「服务内部错误」（2026-09-29 生产实测）。理由同 WxPayConfiguration 同处。
			throw new BizException(NOT_CONFIGURED);
		}
		String tenantId = TenantContextHolder.getTenantId();
		if (StrUtil.isBlank(tenantId)) {
			// 无租户上下文时 findByType 本会 fail loud 查不到配置，此处兜底：不落共享缓存，保持原每次新建
			log.warn("getFactory缺少租户上下文，跳过MultipleFactory缓存");
			return buildFactory(payConfig);
		}
		String cacheKey = tenantId + ':' + PayConstants.PAY_TYPE_2 + ':' + fingerprint(payConfig);
		if (FACTORY_CACHE.size() > CACHE_SIZE_WARN_THRESHOLD) {
			log.warn("MultipleFactory缓存条目数{}超过{}，请检查pay_config指纹字段是否异常波动", FACTORY_CACHE.size(), CACHE_SIZE_WARN_THRESHOLD);
		}
		// computeIfAbsent 保证同 key 只构造一次，setOptions 抛错（如证书文件缺失）不会把坏实例留在缓存
		return FACTORY_CACHE.computeIfAbsent(cacheKey, key -> buildFactory(payConfig));
	}

	private static MultipleFactory buildFactory(PayConfig payConfig) {
		MultipleFactory multipleFactory = new MultipleFactory();
		multipleFactory.setOptions(getOptions(payConfig));
		return multipleFactory;
	}

	private static Config getOptions(PayConfig payConfig) {
		Config config = new Config();
		config.protocol = "https";
		config.gatewayHost = "openapi.alipay.com";
		config.signType = "RSA2";
		config.appId = payConfig.getAppId();

		// 为避免私钥随源码泄露，推荐从文件中读取私钥字符串而不是写入源码中
		config.merchantPrivateKey = payConfig.getMchKey();

		//注：证书文件路径支持设置为文件系统中的路径或CLASS_PATH中的路径，优先从文件系统中加载，加载失败后会继续尝试从CLASS_PATH中加载
		//请填写您的应用公钥证书文件路径，例如：/foo/appCertPublicKey_2019051064521003.crt
		config.merchantCertPath = payConfig.getPrivateCertPath();
		//请填写您的支付宝公钥证书文件路径，例如：/foo/alipayCertPublicKey_RSA2.crt
		config.alipayCertPath = payConfig.getPrivateKeyPath();
		//请填写您的支付宝根证书文件路径，例如：/foo/alipayRootCert.crt
		config.alipayRootCertPath = payConfig.getKeyPath();

		//注：如果采用非证书模式，则无需赋值上面的三个证书路径，改为赋值如下的支付宝公钥字符串即可
		// config.alipayPublicKey = "<-- 请填写您的支付宝公钥，例如：MIIBIjANBg... -->";

		return config;
	}

	/**
	 * 指纹字段 = getOptions 实际读取的全部 pay_config 字段 + 证书文件 stat（model 不参与工厂装配，不进指纹）
	 */
	private static String fingerprint(PayConfig payConfig) {
		return PayConfigFingerprint.of(
				payConfig.getId(),
				payConfig.getAppId(),
				payConfig.getMchKey(),
				payConfig.getPrivateCertPath(),
				payConfig.getPrivateKeyPath(),
				payConfig.getKeyPath(),
				PayConfigFingerprint.fileStat(payConfig.getPrivateCertPath()),
				PayConfigFingerprint.fileStat(payConfig.getPrivateKeyPath()),
				PayConfigFingerprint.fileStat(payConfig.getKeyPath()));
	}
}
