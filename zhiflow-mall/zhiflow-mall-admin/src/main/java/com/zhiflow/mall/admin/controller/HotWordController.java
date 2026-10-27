package com.zhiflow.mall.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.GlobalHotWordService;
import com.zhiflow.mall.common.entity.GlobalHotWord;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 搜索页热搜词（S-10）。C 端 {@code /globalhotword/getTop} 按热度取前 10；热度只在这里维护，C 端不再写。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/hotword")
@Tag(name = "热搜词管理")
public class HotWordController {

	/** 与表结构 {@code global_hot_word.word varchar(50)} 一致。 */
	static final int MAX_WORD_LENGTH = 50;

	private final GlobalHotWordService globalHotWordService;

	/**
	 * 分页列表，按热度从高到低。
	 *
	 * @param word 热词模糊查询
	 */
	@Operation(summary = "热搜词分页列表")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('mall:hotword:index')")
	public R<Page<GlobalHotWord>> getPage(Page<GlobalHotWord> page, String word) {
		return R.ok(globalHotWordService.page(page, Wrappers.<GlobalHotWord>lambdaQuery()
				.eq(GlobalHotWord::getDelFlag, "0")
				.like(StrUtil.isNotBlank(word), GlobalHotWord::getWord, word)
				.orderByDesc(GlobalHotWord::getTotalCount)
				.orderByDesc(GlobalHotWord::getCreateTime)));
	}

	@Operation(summary = "热搜词查询")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:hotword:get')")
	public R<GlobalHotWord> getById(@PathVariable("id") Long id) {
		return R.ok(globalHotWordService.getById(id));
	}

	/** 新增。热度（totalCount）不填按 0。 */
	@Operation(summary = "热搜词新增")
	@SysLog("新增热搜词")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('mall:hotword:add')")
	public R<Boolean> save(@RequestBody GlobalHotWord hotWord) {
		hotWord.setId(null);
		String invalid = normalize(hotWord);
		if (invalid != null) {
			return R.failed(invalid);
		}
		if (globalHotWordService.wordExists(hotWord.getWord(), null)) {
			return R.failed("热搜词「" + hotWord.getWord() + "」已存在");
		}
		return R.ok(globalHotWordService.save(hotWord));
	}

	@Operation(summary = "热搜词修改")
	@SysLog("修改热搜词")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('mall:hotword:edit')")
	public R<Boolean> updateById(@RequestBody GlobalHotWord hotWord) {
		if (hotWord.getId() == null) {
			return R.failed("缺少热搜词 id");
		}
		String invalid = normalize(hotWord);
		if (invalid != null) {
			return R.failed(invalid);
		}
		if (globalHotWordService.wordExists(hotWord.getWord(), hotWord.getId())) {
			return R.failed("热搜词「" + hotWord.getWord() + "」已存在");
		}
		return R.ok(globalHotWordService.updateById(hotWord));
	}

	@Operation(summary = "热搜词删除")
	@SysLog("删除热搜词")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:hotword:del')")
	public R<Boolean> removeById(@PathVariable("id") Long id) {
		return R.ok(globalHotWordService.removeById(id));
	}

	/**
	 * 热词去首尾空格、不能为空、不超过 {@value #MAX_WORD_LENGTH} 字；热度不填按 0、不能为负。
	 *
	 * @return 不合法时的提示；合法返回 null
	 */
	static String normalize(GlobalHotWord hotWord) {
		String word = StrUtil.trim(hotWord.getWord());
		if (StrUtil.isBlank(word)) {
			return "热搜词不能为空";
		}
		if (word.length() > MAX_WORD_LENGTH) {
			return "热搜词最多 " + MAX_WORD_LENGTH + " 个字";
		}
		hotWord.setWord(word);
		// 租户、删除标记、时间戳由框架填充，不收前端传的值
		hotWord.setTenantId(null);
		hotWord.setDelFlag(null);
		hotWord.setCreateTime(null);
		hotWord.setUpdateTime(null);
		if (hotWord.getTotalCount() == null) {
			hotWord.setTotalCount(0L);
		}
		if (hotWord.getTotalCount() < 0) {
			return "热度不能为负数";
		}
		return null;
	}
}
