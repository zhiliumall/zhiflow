/*
 * Copyright (C) 2025 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.pay.admin.controller;

import cn.hutool.core.io.FileUtil;
import cn.hutool.core.util.StrUtil;
import cn.hutool.json.JSONUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.config.HomeDirConfigProperties;
import com.zhiflow.common.core.util.FileUtils;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.UploadValidator;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.pay.admin.support.PayConfigValidator;
import com.zhiflow.pay.common.entity.PayConfig;
import com.zhiflow.pay.admin.service.PayConfigService;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;
import org.springframework.security.access.prepost.PreAuthorize;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * 支付配置
 *
 * @date 2020-05-29 16:37:13
 */
@Slf4j
@RestController
@AllArgsConstructor
@RequestMapping("/payconfig")
@Tag(description = "payconfig", name = "支付配置管理")
public class PayConfigController {

    private final PayConfigService payConfigService;
	private final HomeDirConfigProperties homeDirConfigProperties;

    /**
     * 分页列表
     * @param page 分页对象
     * @param payConfig 支付配置
     * @return
     */
    @Operation(summary = "分页列表")
    @GetMapping("/page")
    @PreAuthorize("@ato.hasAuthority('pay:payconfig:index')")
    public R getPage(Page page, PayConfig payConfig) {
        return R.ok(payConfigService.page(page, Wrappers.query(payConfig)));
    }

    /**
     * 支付配置查询
     * @param id
     * @return R
     */
    @Operation(summary = "支付配置查询")
    @GetMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('pay:payconfig:get')")
    public R getById(@PathVariable("id") String id) {
        return R.ok(payConfigService.getById(id));
    }

    /**
     * 支付配置新增
     * @param payConfig 支付配置
     * @return R
     */
    @Operation(summary = "支付配置新增")
    @SysLog("新增支付配置")
    @PostMapping
    @PreAuthorize("@ato.hasAuthority('pay:payconfig:add')")
    public R save(@RequestBody PayConfig payConfig) {
        // 按渠道校验必填项。此前保存路径上**一次校验都没有**：入参没 @Valid、
        // 类上没 @Validated，实体上那批 @NotNull 全是摆设，真正在拦的只有库的 NOT NULL
        // （报错难看）和后台表单的 required（绕过表单就没了）。
        String invalid = PayConfigValidator.validate(payConfig);
        if (invalid != null) {
            return R.failed(invalid);
        }
        return R.ok(payConfigService.save(payConfig));
    }

    /**
     * 支付配置修改
     * @param payConfig 支付配置
     * @return R
     */
    @Operation(summary = "支付配置修改")
    @SysLog("修改支付配置")
    @PutMapping
    @PreAuthorize("@ato.hasAuthority('pay:payconfig:edit')")
    public R updateById(@RequestBody PayConfig payConfig) {
        String invalid = PayConfigValidator.validate(payConfig);
        if (invalid != null) {
            return R.failed(invalid);
        }
        return R.ok(payConfigService.updateById(payConfig));
    }

    /**
     * 支付配置删除
     * @param id
     * @return R
     */
    @Operation(summary = "支付配置删除")
    @SysLog("删除支付配置")
    @DeleteMapping("/{id}")
    @PreAuthorize("@ato.hasAuthority('pay:payconfig:del')")
    public R removeById(@PathVariable String id) {
        return R.ok(payConfigService.removeById(id));
    }

	/**
	 * 支付证书允许的扩展名。前端三个上传框（配置/证书路径、私钥路径、公钥证书路径，
	 * payconfig/index.vue:823）没有 accept 限制，但微信支付实际只用
	 * apiclient_cert.p12 / apiclient_cert.pem / apiclient_key.pem 这三类。
	 */
	private static final java.util.Set<String> PAY_CERT_EXTENSIONS =
			java.util.Set.of("p12", "pfx", "pem", "key", "crt", "cer");

	/** 支付证书都是几 KB 的文本或 PKCS#12 容器，2MB 是「传错文件」的判据而不是业务上限 */
	private static final long PAY_CERT_MAX_BYTES = 2L * 1024 * 1024;

	/**
	 * 上传支付证书
	 * @param mulFile
	 * @return
	 */
	@Operation(summary = "上传支付证书")
	@SysLog("上传支付证书")
	@PostMapping("/cert/upload")
	@PreAuthorize("@ato.hasAuthority('pay:payconfig:edit')")
	public String uploadFile(@RequestParam("file") MultipartFile mulFile) throws IOException {
		// 落盘到 {home}/cert/{tenantId}/，文件名取自 createTempFile 生成的临时名（不含用户输入），
		// 故这里判的是「传上来的是不是证书」，不是路径穿越
		UploadValidator.requireNonEmpty(mulFile);
		UploadValidator.requireMaxSize(mulFile, PAY_CERT_MAX_BYTES);
		UploadValidator.requireExtensionIn(mulFile, PAY_CERT_EXTENSIONS);
		File file = FileUtils.multipartFileToFile(mulFile);
		Map<Object, Object> responseData = new HashMap<>();
		String home = homeDirConfigProperties.getHomeDir();
		String dir = StrUtil.format("{}/{}/{}/", home,"cert", TenantContextHolder.getTenantId());
		File file2 = FileUtil.writeBytes(FileUtil.readBytes(file), dir + file.getName());
		responseData.put("link", file2.getPath());
		return JSONUtil.toJsonStr(responseData);
	}

	/**
	 * 根据支付类型查询支付配置。
	 *
	 * <p><b>⚠️ 原注释写的是「只返回 appId、subAppId」，那是假的</b> —— {@code findByType}
	 * 是一个裸的 {@code selectOne}，把整个 {@link PayConfig} 实体原样返回，其中包含
	 * {@code mchKey}（商户密钥）、{@code apiV3Key}、{@code certSerialNo}、
	 * {@code privateKeyPath} / {@code privateCertPath}（证书路径）。
	 *
	 * <p>2026-08-18 之前本方法**没有任何 {@code @PreAuthorize}** ⇒ 任何已登录的后台账号
	 * （包括只该看订单的客服岗）都能读到微信支付商户密钥。匿名不可达（424），
	 * 所以不是公网泄漏，但仍是明确的越权读。
	 *
	 * <p>修法选的是**加权限码而不是剥字段**：唯一调用方是 admin 的支付配置页
	 * （{@code src/api/pay/payconfig.ts:41}），它本来就要编辑这些字段，剥了会打断编辑。
	 * 对照：mall-api 侧的同名端点返回的是 {@code PayConfigVO}，已经不含敏感字段，无需改。
	 *
	 * @param type
	 * @return R
	 */
	@Operation(summary = "根据支付类型查询支付配置")
	@GetMapping("/bytype/{type}")
	@PreAuthorize("@ato.hasAuthority('pay:payconfig:get')")
	public R getByType(@PathVariable("type") String type) {
		return R.ok(payConfigService.findByType(type));
	}
}
