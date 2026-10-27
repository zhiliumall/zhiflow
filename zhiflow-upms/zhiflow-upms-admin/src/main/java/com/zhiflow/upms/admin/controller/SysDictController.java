package com.zhiflow.upms.admin.controller;

import com.zhiflow.upms.admin.projection.UpmsInsideProjections;
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import com.zhiflow.upms.common.dto.SysDictQuery;
import com.zhiflow.upms.common.dto.SysDictInfo;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.security.annotation.Inside;
import com.zhiflow.upms.admin.service.SysDictValueService;
import com.zhiflow.upms.admin.service.SysDictService;
import com.zhiflow.upms.common.entity.SysDict;
import com.zhiflow.upms.common.entity.SysDictValue;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.swagger.v3.oas.annotations.Operation;
import lombok.AllArgsConstructor;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import com.zhiflow.common.core.constant.CacheConstants;
import jakarta.validation.Valid;

import java.util.List;

/**
 * <p>
 * 字典表 前端控制器
 * </p>
 *
 * @since 2017-11-19
 */
@RestController
@AllArgsConstructor
@RequestMapping("/dict")
@Tag(description = "dict", name = "字典管理模块")
public class SysDictController {
	private final SysDictService sysDictService;
	private final SysDictValueService sysDictValueService;

	/**
	 * 通过ID查询字典信息
	 *
	 * @param id ID
	 * @return 字典信息
	 */
	@Operation(summary = "通过ID查询字典信息")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:dict:get')")
	public R getById(@PathVariable String id) {
		return R.ok(sysDictService.getById(id));
	}

	/**
	 * 分页查询字典信息
	 *
	 * @param page 分页对象
	 * @return 分页对象
	 */
	@Operation(summary = "分页列表")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('sys:dict:index')")
	public R<IPage> getDictPage(Page page, SysDict sysDict) {
		return R.ok(sysDictService.page(page, Wrappers.query(sysDict)));
	}

	/**
	 * 查询短信配置（服务间调用）
	 * @return R
	 */
	@Inside
	@Operation(summary = "分页列表")
	@PostMapping("/inside/page")
	public R<List<SysDictInfo>> getInside(@RequestBody SysDictQuery sysDict) {
		return R.ok(UpmsInsideProjections
				.dicts(sysDictService.list(Wrappers.query(UpmsInsideProjections.dictQuery(sysDict)))));
	}
	/**
	 * 通过字典类型查找字典
	 *
	 * @param type 类型
	 * @return 同类型字典
	 */
	@Operation(summary = "通过字典类型查找")
	@GetMapping("/inside/type/{type}")
	@Inside
	// 与下面 /type/{type} 同一个缓存、返回类型不同（DTO vs 实体），key 必须分开：
	// 共用 #type 时谁先被调用谁的结果就占住缓存，另一边反序列化出来强转失败（发货时取快递公司字典报 ClassCastException）
	@Cacheable(value = CacheConstants.DICT_CACHE, key = "'inside:' + #type", unless = "#result == null")
	public R<List<SysDictValueInfo>> getInsideDictByType(@PathVariable("type") String type) {
		return R.ok(UpmsInsideProjections.dictValues(sysDictValueService.list(Wrappers
				.<SysDictValue>query().lambda()
				.eq(SysDictValue::getType, type))));
	}
	/**
	 * 通过字典类型查找字典
	 *
	 * @param type 类型
	 * @return 同类型字典
	 */
	@Operation(summary = "通过字典类型查找")
	@GetMapping("/type/{type}")
	@Cacheable(value = CacheConstants.DICT_CACHE, key = "#type", unless = "#result == null")
	public R getDictByType(@PathVariable("type") String type) {
		return R.ok(sysDictValueService.list(Wrappers
				.<SysDictValue>query().lambda()
				.eq(SysDictValue::getType, type)));
	}

	/**
	 * 添加字典
	 *
	 * @param sysDict 字典信息
	 * @return ok、false
	 */
	@Operation(summary = "添加字典")
	@SysLog("添加字典")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('sys:dict:add')")
	public R save(@Valid @RequestBody SysDict sysDict) {
		return R.ok(sysDictService.save(sysDict));
	}

	/**
	 * 删除字典，并且清除字典缓存
	 *
	 * @param id ID
	 * @return R
	 */
	@Operation(summary = "删除字典，并且清除字典缓存")
	@SysLog("删除字典")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:dict:del')")
	public R removeById(@PathVariable String id) {
		return sysDictService.removeDict(id);
	}

	/**
	 * 修改字典
	 *
	 * @param sysDict 字典信息
	 * @return ok/false
	 */
	@Operation(summary = "修改字典")
	@PutMapping
	@SysLog("修改字典")
	@PreAuthorize("@ato.hasAuthority('sys:dict:edit')")
	public R updateById(@Valid @RequestBody SysDict sysDict) {
		return sysDictService.updateDict(sysDict);
	}

	/**
	 * 分页查询
	 *
	 * @param page        分页对象
	 * @param sysDictValue 字典项
	 * @return
	 */
	@Operation(summary = "分页查询")
	// [R6 批2 2026-08-17] 字典**项**的这 5 个端点原先一个权限码都没有（任何后台账号可增删改），
	// 而同一页的字典**类型**端点早就挂了 sys:dict:*。两者是同一个「字典管理」页、同一批 4 个角色，
	// 补上同一套码，人群零变化。
	@GetMapping("/item/page")
	@PreAuthorize("@ato.hasAuthority('sys:dict:index')")
	public R getSysDictItemPage(Page page, SysDictValue sysDictValue) {
		return R.ok(sysDictValueService.page(page, Wrappers.query(sysDictValue)));
	}


	/**
	 * 通过id查询字典项
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id查询字典项")
	@GetMapping("/item/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:dict:get')")
	public R getDictItemById(@PathVariable("id") String id) {
		return R.ok(sysDictValueService.getById(id));
	}

	/**
	 * 新增字典项
	 *
	 * @param sysDictValue 字典项
	 * @return R
	 */
	@Operation(summary = "新增字典项")
	@SysLog("新增字典项")
	@PostMapping("/item")
	@PreAuthorize("@ato.hasAuthority('sys:dict:add')")
	@CacheEvict(value = CacheConstants.DICT_CACHE, allEntries = true)
	public R save(@RequestBody SysDictValue sysDictValue) {
		return R.ok(sysDictValueService.save(sysDictValue));
	}

	/**
	 * 修改字典项
	 *
	 * @param sysDictValue 字典项
	 * @return R
	 */
	@Operation(summary = "修改字典项")
	@SysLog("修改字典项")
	@PutMapping("/item")
	@PreAuthorize("@ato.hasAuthority('sys:dict:edit')")
	public R updateById(@RequestBody SysDictValue sysDictValue) {
		return sysDictValueService.updateDictItem(sysDictValue);
	}

	/**
	 * 通过id删除字典项
	 *
	 * @param id id
	 * @return R
	 */
	@Operation(summary = "通过id删除字典项")
	@SysLog("删除字典项")
	@DeleteMapping("/item/{id}")
	@PreAuthorize("@ato.hasAuthority('sys:dict:del')")
	public R removeDictItemById(@PathVariable String id) {
		return sysDictValueService.removeDictItem(id);
	}
}
