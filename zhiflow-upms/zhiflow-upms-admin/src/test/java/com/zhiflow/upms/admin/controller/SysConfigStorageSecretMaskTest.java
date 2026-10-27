package com.zhiflow.upms.admin.controller;

import com.zhiflow.common.core.util.R;
import com.zhiflow.common.core.util.SensitiveUtils;
import com.zhiflow.upms.admin.service.SysConfigStorageService;
import com.zhiflow.upms.common.entity.SysConfigStorage;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 存储配置 AK/SK 不出明文（实测曾发现 PUT 响应体回了明文 accessKeySecret）。
 * 读接口打码；保存时空值 / 打码值 = 不修改。
 */
class SysConfigStorageSecretMaskTest {

	private static SysConfigStorage row(String id, String ak, String sk) {
		SysConfigStorage s = new SysConfigStorage();
		s.setId(id);
		s.setAccessKeyId(ak);
		s.setAccessKeySecret(sk);
		s.setBucket("b");
		return s;
	}

	@Test
	@DisplayName("查询：AK / SK 都打码，其它字段照常，且不改缓存里的原对象")
	void getMasksBoth() {
		SysConfigStorageService svc = mock(SysConfigStorageService.class);
		SysConfigStorage stored = row("1", "AKID-plain-123", "SK-plain-456");
		when(svc.getOne(any())).thenReturn(stored);

		SysConfigStorage out = (SysConfigStorage) new SysConfigStorageController(svc).get().getData();

		assertThat(out.getAccessKeyId()).isEqualTo(SensitiveUtils.process("AKID-plain-123")).doesNotContain("plain");
		assertThat(out.getAccessKeySecret()).isEqualTo(SensitiveUtils.process("SK-plain-456")).doesNotContain("plain");
		assertThat(out.getBucket()).isEqualTo("b");
		assertThat(stored.getAccessKeySecret()).isEqualTo("SK-plain-456");
	}

	@Test
	@DisplayName("修改：回传打码值 / 空值 = 沿用旧值；响应体打码")
	void putKeepsOldWhenMaskedOrBlank() {
		SysConfigStorageService svc = mock(SysConfigStorageService.class);
		when(svc.getById("1")).thenReturn(row("1", "AKID-plain-123", "SK-plain-456"));
		SysConfigStorage body = row("1", SensitiveUtils.process("AKID-plain-123"), "");

		R r = new SysConfigStorageController(svc).updateById(body);

		ArgumentCaptor<SysConfigStorage> saved = ArgumentCaptor.forClass(SysConfigStorage.class);
		verify(svc).updateById(saved.capture());
		assertThat(saved.getValue().getAccessKeyId()).isEqualTo("AKID-plain-123");
		assertThat(saved.getValue().getAccessKeySecret()).isEqualTo("SK-plain-456");
		SysConfigStorage resp = (SysConfigStorage) r.getData();
		assertThat(resp.getAccessKeyId()).doesNotContain("plain");
		assertThat(resp.getAccessKeySecret()).doesNotContain("plain");
	}

	@Test
	@DisplayName("修改：填了新密钥就用新值；新增时响应体也打码")
	void putUsesNewValues() {
		SysConfigStorageService svc = mock(SysConfigStorageService.class);
		when(svc.getById("1")).thenReturn(row("1", "AKID-plain-123", "SK-plain-456"));
		new SysConfigStorageController(svc).updateById(row("1", "AK-new", "SK-new-789"));
		ArgumentCaptor<SysConfigStorage> saved = ArgumentCaptor.forClass(SysConfigStorage.class);
		verify(svc).updateById(saved.capture());
		assertThat(saved.getValue().getAccessKeyId()).isEqualTo("AK-new");
		assertThat(saved.getValue().getAccessKeySecret()).isEqualTo("SK-new-789");

		R created = new SysConfigStorageController(svc).updateById(row(null, "AK-first", "SK-first-000"));
		assertThat(((SysConfigStorage) created.getData()).getAccessKeySecret()).doesNotContain("first");
	}

	@Test
	@DisplayName("修改：id 不存在时返回失败而不是 500")
	void putMissingRow() {
		SysConfigStorageService svc = mock(SysConfigStorageService.class);
		R r = new SysConfigStorageController(svc).updateById(row("404", "a", "b"));
		assertThat(r.getCode()).isNotZero();
	}
}
