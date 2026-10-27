package com.zhiflow.upms.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.constant.CacheConstants;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.common.security.entity.BaseUser;
import com.zhiflow.common.security.util.SecurityUtils;
import org.springframework.security.access.prepost.PreAuthorize;
import com.zhiflow.upms.admin.service.SysLogLoginService;
import com.zhiflow.upms.admin.service.SysLogService;
import com.zhiflow.upms.admin.service.SysUserSecurityProfileService;
import com.zhiflow.upms.admin.service.SysUserService;
import com.zhiflow.upms.common.entity.SysLogLogin;
import com.zhiflow.upms.common.entity.SysUser;
import com.zhiflow.upms.common.entity.SysUserSecurityProfile;
import com.zhiflow.upms.common.dto.TotpLoginRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.NotBlank;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.web.bind.annotation.*;
import com.zhiflow.common.security.annotation.Inside;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/** Security-center APIs are always restricted to the authenticated account. */
@RestController
// 必须是 @RequiredArgsConstructor 而不是 @AllArgsConstructor：后者会把下面那个 @Value 注解的
// **非 final** 字段 totpEncryptionKey 也编进构造器，Spring 于是去找一个 java.lang.String bean，
// 找不到就 APPLICATION FAILED TO START（2026-08-02 出过事故：upms-admin 崩溃循环）。
// @RequiredArgsConstructor 只收 final 字段，@Value 字段回到正常的字段注入。
@RequiredArgsConstructor
@RequestMapping("/user/security")
@Tag(name = "account-security", description = "Account security center")
public class SysUserSecurityController {
	private static final SecureRandom RANDOM = new SecureRandom();
	private static final int TOTP_STEP_SECONDS = 30;

	private final SysUserSecurityProfileService securityProfileService;
	private final SysUserService sysUserService;
	private final SysLogService sysLogService;
	private final SysLogLoginService sysLogLoginService;
	private final RedisTemplate redisTemplate;
	private final CacheManager cacheManager;

	@Value("${mall.security.account.totp-encryption-key:}")
	private String totpEncryptionKey;

	@Operation(summary = "Current user's security overview")
	@GetMapping("/overview")
	public R<Map<String, Object>> overview() {
		BaseUser current = currentUser();
		SysUser user = sysUserService.getById(current.getId());
		if (user == null) return R.failed("当前账号不存在");
		SysUserSecurityProfile profile = profile(current);
		Map<String, Object> result = new LinkedHashMap<>();
		result.put("phoneVerified", profile.getPhoneVerifiedAt() != null);
		result.put("emailVerified", profile.getEmailVerifiedAt() != null);
		result.put("totpEnabled", Boolean.TRUE.equals(profile.getTotpEnabled()));
		result.put("totpAvailable", StrUtil.isNotBlank(totpEncryptionKey));
		result.put("realNameStatus", StrUtil.blankToDefault(profile.getRealNameStatus(), "UNVERIFIED"));
		result.put("realName", profile.getRealName());
		result.put("identityLastFour", profile.getIdentityLastFour());
		int score = 50;
		if (profile.getPhoneVerifiedAt() != null) score += 15;
		if (profile.getEmailVerifiedAt() != null) score += 15;
		if (Boolean.TRUE.equals(profile.getTotpEnabled())) score += 10;
		if ("VERIFIED".equals(profile.getRealNameStatus())) score += 10;
		result.put("score", score);
		return R.ok(result);
	}

	@Operation(summary = "Prepare merchant TOTP enrollment")
	@SysLog("初始化本人两步验证")
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/totp/setup")
	public R<Map<String, String>> setupTotp() {
		BaseUser current = currentUser();
		if (StrUtil.isBlank(totpEncryptionKey)) return R.failed("两步验证服务尚未配置");
		String secret = base32(randomBytes(20));
		SysUserSecurityProfile profile = profile(current);
		profile.setTotpSecretCiphertext(encrypt(secret));
		profile.setTotpEnabled(false);
		save(profile);
		String label = "Zhiflow:" + current.getUsername();
		String uri = "otpauth://totp/" + label + "?secret=" + secret + "&issuer=Zhiflow&algorithm=SHA1&digits=6&period=30";
		return R.ok(Map.of("secret", secret, "otpauthUrl", uri));
	}

	@Operation(summary = "Confirm or disable merchant TOTP")
	@SysLog("更新本人两步验证")
	@PreAuthorize("isAuthenticated()")
	@PostMapping("/totp")
	public R<Boolean> updateTotp(@RequestBody TotpRequest request) {
		BaseUser current = currentUser();
		if (StrUtil.isBlank(totpEncryptionKey)) return R.failed("两步验证服务尚未配置");
		SysUserSecurityProfile profile = profile(current);
		if (request == null || StrUtil.isBlank(request.getCode()) || StrUtil.isBlank(profile.getTotpSecretCiphertext())) return R.failed("请先完成动态码验证");
		if (!matchesTotp(decrypt(profile.getTotpSecretCiphertext()), request.getCode())) return R.failed("动态码错误");
		profile.setTotpEnabled(!Boolean.FALSE.equals(request.getEnabled()));
		if (Boolean.FALSE.equals(request.getEnabled())) profile.setTotpSecretCiphertext(null);
		save(profile);
		return R.ok(Boolean.TRUE);
	}

	/** Used only by zhiflow-auth after primary credentials have succeeded. */
	@Inside
	@PostMapping("/totp/login-check")
	public R<Boolean> checkTotpForLogin(@RequestBody TotpLoginRequest request) {
		if (request == null || StrUtil.hasBlank(request.getUserId(), request.getTenantId())) return R.failed("动态码校验请求不合法");
		SysUserSecurityProfile profile = securityProfileService.getById(request.getUserId());
		if (profile == null || !Boolean.TRUE.equals(profile.getTotpEnabled())) return R.ok(Boolean.TRUE);
		if (!StrUtil.equals(request.getTenantId(), profile.getTenantId()) || StrUtil.isBlank(request.getCode())) return R.ok(Boolean.FALSE);
		return R.ok(matchesTotp(decrypt(profile.getTotpSecretCiphertext()), request.getCode()));
	}

	@Operation(summary = "Current user's operation or login logs")
	@GetMapping("/logs")
	public R<?> logs(@RequestParam(defaultValue = "operation") String type, Page page) {
		BaseUser current = currentUser();
		if ("login".equals(type)) {
			return R.ok(sysLogLoginService.page(page, Wrappers.<SysLogLogin>lambdaQuery()
					.eq(SysLogLogin::getCreateId, current.getId()).eq(SysLogLogin::getTenantId, current.getTenantId())
					.orderByDesc(SysLogLogin::getCreateTime)));
		}
		// 操作日志实体只能写全限定名：简名 SysLog 已被本类大量使用的审计注解
		// com.zhiflow.common.log.annotation.SysLog 占用，两者同时 import 会触发
		// single-type-import 冲突。该冲突发生在 javac 的解析期，会**中止整模块的 Lombok 注解处理**，
		// 级联出上百条「找不到符号 变量 log」，让 upms-admin 整个编译不过（2026-08-01 修）。
		return R.ok(sysLogService.page(page, Wrappers.<com.zhiflow.upms.common.entity.SysLog>lambdaQuery()
				.eq(com.zhiflow.upms.common.entity.SysLog::getCreateId, current.getId())
				.eq(com.zhiflow.upms.common.entity.SysLog::getTenantId, current.getTenantId())
				.orderByDesc(com.zhiflow.upms.common.entity.SysLog::getCreateTime)));
	}

	private BaseUser currentUser() {
		BaseUser current = SecurityUtils.getUser();
		if (current == null || StrUtil.hasBlank(current.getId(), current.getTenantId())) throw new IllegalStateException("未登录");
		return current;
	}

	private SysUserSecurityProfile profile(BaseUser current) {
		SysUserSecurityProfile profile = securityProfileService.getById(current.getId());
		if (profile != null) {
			if (!StrUtil.equals(current.getTenantId(), profile.getTenantId())) throw new IllegalStateException("账号安全资料租户不匹配");
			return profile;
		}
		profile = new SysUserSecurityProfile();
		profile.setUserId(current.getId());
		profile.setTenantId(current.getTenantId());
		profile.setTotpEnabled(false);
		profile.setRealNameStatus("UNVERIFIED");
		profile.setCreateTime(LocalDateTime.now());
		return profile;
	}

	private void save(SysUserSecurityProfile profile) {
		profile.setUpdateTime(LocalDateTime.now());
		securityProfileService.saveOrUpdate(profile);
	}

	private String emailKey(String userId) { return "account:security:email:" + userId; }

	private void evictUser(BaseUser current) {
		if (cacheManager.getCache(CacheConstants.USER_CACHE) != null) cacheManager.getCache(CacheConstants.USER_CACHE).evict(current.getUsername());
	}

	private String encrypt(String value) {
		try {
			byte[] iv = randomBytes(12);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(128, iv));
			byte[] encrypted = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
			byte[] payload = new byte[iv.length + encrypted.length];
			System.arraycopy(iv, 0, payload, 0, iv.length);
			System.arraycopy(encrypted, 0, payload, iv.length, encrypted.length);
			return Base64.getEncoder().encodeToString(payload);
		} catch (Exception e) { throw new IllegalStateException("两步验证密钥处理失败", e); }
	}

	private String decrypt(String value) {
		try {
			byte[] payload = Base64.getDecoder().decode(value);
			byte[] iv = java.util.Arrays.copyOfRange(payload, 0, 12);
			byte[] encrypted = java.util.Arrays.copyOfRange(payload, 12, payload.length);
			Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
			cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, iv));
			return new String(cipher.doFinal(encrypted), StandardCharsets.UTF_8);
		} catch (Exception e) { throw new IllegalStateException("两步验证密钥不可用", e); }
	}

	private SecretKeySpec key() throws Exception {
		return new SecretKeySpec(MessageDigest.getInstance("SHA-256").digest(totpEncryptionKey.getBytes(StandardCharsets.UTF_8)), "AES");
	}

	private boolean matchesTotp(String secret, String code) {
		if (!code.matches("^\\d{6}$")) return false;
		long counter = System.currentTimeMillis() / 1000L / TOTP_STEP_SECONDS;
		for (long offset = -1; offset <= 1; offset++) if (totp(secret, counter + offset).equals(code)) return true;
		return false;
	}

	private String totp(String secret, long counter) {
		try {
			javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA1");
			mac.init(new SecretKeySpec(base32Decode(secret), "HmacSHA1"));
			byte[] data = java.nio.ByteBuffer.allocate(8).putLong(counter).array();
			byte[] hash = mac.doFinal(data);
			int offset = hash[hash.length - 1] & 0xf;
			int value = ((hash[offset] & 0x7f) << 24) | ((hash[offset + 1] & 0xff) << 16) | ((hash[offset + 2] & 0xff) << 8) | (hash[offset + 3] & 0xff);
			return String.format("%06d", value % 1_000_000);
		} catch (Exception e) { throw new IllegalStateException("动态码计算失败", e); }
	}

	private static byte[] randomBytes(int length) { byte[] bytes = new byte[length]; RANDOM.nextBytes(bytes); return bytes; }
	private static final char[] BASE32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".toCharArray();
	private static String base32(byte[] input) { StringBuilder out = new StringBuilder(); int buffer = 0, bits = 0; for (byte item : input) { buffer = (buffer << 8) | (item & 255); bits += 8; while (bits >= 5) { out.append(BASE32[(buffer >> (bits - 5)) & 31]); bits -= 5; } } if (bits > 0) out.append(BASE32[(buffer << (5 - bits)) & 31]); return out.toString(); }
	private static byte[] base32Decode(String input) { java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream(); int buffer = 0, bits = 0; for (char c : input.toUpperCase().toCharArray()) { int value = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567".indexOf(c); if (value < 0) continue; buffer = (buffer << 5) | value; bits += 5; if (bits >= 8) { out.write((buffer >> (bits - 8)) & 255); bits -= 8; } } return out.toByteArray(); }

	@Data public static class TotpRequest { private Boolean enabled; @NotBlank private String code; }
}
