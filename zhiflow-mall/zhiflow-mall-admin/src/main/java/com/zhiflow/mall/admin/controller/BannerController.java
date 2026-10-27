package com.zhiflow.mall.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.service.BannerService;
import com.zhiflow.mall.common.entity.Banner;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
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
 * 首页轮播图（S-10）。C 端查询见 mall-api 的 {@code /banner/list}。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/banner")
@Tag(name = "轮播图管理")
public class BannerController {

	private final BannerService bannerService;

	/**
	 * 分页列表，按排序、创建时间。
	 *
	 * @param title  标题模糊查询
	 * @param enable 1 启用 / 0 停用；空 = 全部
	 */
	@Operation(summary = "轮播图分页列表")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('mall:banner:index')")
	public R<Page<Banner>> getPage(Page<Banner> page, String title, String enable) {
		return R.ok(bannerService.page(page, Wrappers.<Banner>lambdaQuery()
				.like(StrUtil.isNotBlank(title), Banner::getTitle, title)
				.eq(StrUtil.isNotBlank(enable), Banner::getEnable, enable)
				.orderByAsc(Banner::getSort)
				.orderByDesc(Banner::getCreateTime)));
	}

	@Operation(summary = "轮播图查询")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:banner:get')")
	public R<Banner> getById(@PathVariable("id") String id) {
		return R.ok(bannerService.getById(id));
	}

	@Operation(summary = "轮播图新增")
	@SysLog("新增轮播图")
	@PostMapping
	@PreAuthorize("@ato.hasAuthority('mall:banner:add')")
	public R<Boolean> save(@Valid @RequestBody Banner banner) {
		banner.setId(null);
		String invalid = normalize(banner);
		if (invalid != null) {
			return R.failed(invalid);
		}
		return R.ok(bannerService.save(banner));
	}

	@Operation(summary = "轮播图修改")
	@SysLog("修改轮播图")
	@PutMapping
	@PreAuthorize("@ato.hasAuthority('mall:banner:edit')")
	public R<Boolean> updateById(@Valid @RequestBody Banner banner) {
		if (StrUtil.isBlank(banner.getId())) {
			return R.failed("缺少轮播图 id");
		}
		String invalid = normalize(banner);
		if (invalid != null) {
			return R.failed(invalid);
		}
		return R.ok(bannerService.updateById(banner));
	}

	@Operation(summary = "轮播图删除")
	@SysLog("删除轮播图")
	@DeleteMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:banner:del')")
	public R<Boolean> removeById(@PathVariable("id") String id) {
		return R.ok(bannerService.removeById(id));
	}

	/**
	 * 排序缺省 0、启用状态缺省启用；跳转地址去首尾空格，空串当作不跳转。
	 *
	 * @return 不合法时的提示；合法返回 null
	 */
	static String normalize(Banner banner) {
		// 租户、删除标记、时间戳由框架填充，不收前端传的值
		banner.setTenantId(null);
		banner.setDelFlag(null);
		banner.setCreateTime(null);
		banner.setUpdateTime(null);
		if (banner.getSort() == null) {
			banner.setSort(0);
		}
		if (StrUtil.isBlank(banner.getEnable())) {
			banner.setEnable("1");
		}
		String link = StrUtil.emptyToNull(StrUtil.trim(banner.getLinkUrl()));
		banner.setLinkUrl(link);
		// H5 拿到地址直接跳：只放站内路由和 http(s)，挡住 javascript: / data: 之类会在页面里执行的地址
		if (link != null && !(link.startsWith("/") && !link.startsWith("//"))
				&& !StrUtil.startWithIgnoreCase(link, "https://") && !StrUtil.startWithIgnoreCase(link, "http://")) {
			return "跳转地址只能是站内路由（以 / 开头）或 http(s) 地址";
		}
		return null;
	}
}
