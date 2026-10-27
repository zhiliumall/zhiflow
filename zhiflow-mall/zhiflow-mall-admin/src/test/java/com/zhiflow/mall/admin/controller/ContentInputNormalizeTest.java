package com.zhiflow.mall.admin.controller;

import com.zhiflow.mall.common.entity.Banner;
import com.zhiflow.mall.common.entity.GlobalHotWord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * 轮播图、热搜词的入参规整（S-10）：缺省值、系统字段不收前端值、跳转地址白名单、热词长度与热度。
 */
class ContentInputNormalizeTest {

	@Test
	@DisplayName("轮播图：排序缺省 0、默认启用、空跳转地址当作不跳转，系统字段清空")
	void bannerDefaults() {
		Banner b = banner("  ");
		b.setTenantId("999");
		b.setDelFlag("1");
		b.setCreateTime(LocalDateTime.now());

		assertNull(BannerController.normalize(b));
		assertEquals(0, b.getSort());
		assertEquals("1", b.getEnable());
		assertNull(b.getLinkUrl());
		assertNull(b.getTenantId());
		assertNull(b.getDelFlag());
		assertNull(b.getCreateTime());
	}

	@Test
	@DisplayName("轮播图跳转地址：站内路由与 http(s) 放行，javascript: / data: / 协议相对地址拒绝")
	void bannerLinkWhitelist() {
		assertNull(BannerController.normalize(banner("/pages/goods/detail?id=1")));
		assertNull(BannerController.normalize(banner(" https://example.com/a ")));
		assertNull(BannerController.normalize(banner("HTTP://example.com")));
		assertNotNull(BannerController.normalize(banner("javascript:alert(1)")));
		assertNotNull(BannerController.normalize(banner("data:text/html,<script>")));
		assertNotNull(BannerController.normalize(banner("//evil.example.com")));
		assertNotNull(BannerController.normalize(banner("pages/goods")));
	}

	@Test
	@DisplayName("热搜词：去首尾空格、不能为空、不超过 50 字；热度缺省 0、不能为负")
	void hotWordRules() {
		GlobalHotWord w = hotWord("  连衣裙 ", null);
		w.setDelFlag("1");
		assertNull(HotWordController.normalize(w));
		assertEquals("连衣裙", w.getWord());
		assertEquals(0L, w.getTotalCount());
		assertNull(w.getDelFlag());

		assertNotNull(HotWordController.normalize(hotWord("   ", 1L)));
		assertNotNull(HotWordController.normalize(hotWord("字".repeat(HotWordController.MAX_WORD_LENGTH + 1), 1L)));
		assertNull(HotWordController.normalize(hotWord("字".repeat(HotWordController.MAX_WORD_LENGTH), 1L)));
		assertNotNull(HotWordController.normalize(hotWord("连衣裙", -1L)));
	}

	private static Banner banner(String link) {
		Banner b = new Banner();
		b.setTitle("t");
		b.setImageUrl("https://img.example.com/1.png");
		b.setLinkUrl(link);
		return b;
	}

	private static GlobalHotWord hotWord(String word, Long count) {
		GlobalHotWord w = new GlobalHotWord();
		w.setWord(word);
		w.setTotalCount(count);
		return w;
	}
}
