package com.zhiflow.mall.admin.controller;

import cn.hutool.core.util.StrUtil;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.log.annotation.SysLog;
import com.zhiflow.mall.admin.mapper.PayExceptionMapper;
import com.zhiflow.mall.admin.service.impl.PayExceptionDisposalService;
import com.zhiflow.mall.common.entity.PayException;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 差错池（支付与订单状态机 v1.2 第六章）——后台只读列表与详情（阶段 0 · 0.2b）。
 *
 * <p>最小版：池里只有 LATE_PAID / DUP_PAY；唯一动作「标记已处理」（必填备注、留痕，
 * {@link PayExceptionDisposalService}）。退款由人在渠道商户后台手工做。权限复用 {@code mall:orderinfo:edit}。
 *
 * <p>权限复用 {@code mall:orderinfo:index}（能看订单的人能看到订单的资金差错）：新权限码需要先写生产
 * {@code sys_menu} 并刷新快照（{@code PermissionCodeExistenceTest}），阶段 2 加处置按钮时一并新建
 * {@code mall:payexception:*}，菜单在基线迁移里。
 */
@RestController
@RequiredArgsConstructor
@RequestMapping("/payexception")
@Tag(description = "payexception", name = "差错池")
public class PayExceptionController {

	private final PayExceptionMapper payExceptionMapper;

	private final PayExceptionDisposalService disposalService;

	/** 标记请求体：备注必填。 */
	public record ResolveRequest(String note) {
	}

	@Operation(summary = "差错：标记已处理（人工在渠道后台退款 / 核实后回来标记，必填备注）")
	@SysLog("差错：标记已处理")
	@PutMapping("/{id}/resolve")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:edit')")
	public R<Boolean> resolve(@PathVariable("id") String id, @RequestBody ResolveRequest body) {
		disposalService.resolve(id, body == null ? null : body.note());
		return R.ok(Boolean.TRUE);
	}

	@Operation(summary = "差错池分页（按入池时间倒序）")
	@GetMapping("/page")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:index')")
	public R<Page<PayException>> page(Page<PayException> page,
			@RequestParam(value = "type", required = false) String type,
			@RequestParam(value = "status", required = false) String status,
			@RequestParam(value = "orderNo", required = false) String orderNo) {
		return R.ok(payExceptionMapper.selectPage(page, query(type, status, orderNo)));
	}

	@Operation(summary = "差错详情")
	@GetMapping("/{id}")
	@PreAuthorize("@ato.hasAuthority('mall:orderinfo:index')")
	public R<PayException> getById(@PathVariable("id") String id) {
		return R.ok(payExceptionMapper.selectById(id));
	}

	static LambdaQueryWrapper<PayException> query(String type, String status, String orderNo) {
		return Wrappers.<PayException>lambdaQuery()
				.eq(StrUtil.isNotBlank(type), PayException::getType, type)
				.eq(StrUtil.isNotBlank(status), PayException::getStatus, status)
				.eq(StrUtil.isNotBlank(orderNo), PayException::getOrderNo, StrUtil.trim(orderNo))
				.orderByDesc(PayException::getCreateTime);
	}
}
