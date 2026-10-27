package com.zhiflow.common.core.util;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.io.Serial;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 批量操作结果：部分成功语义。
 *
 * <p>批量端点不得返回「整体成败」——一条失败不能让前端把整批报成功，也不能把已成功的条目报失败。
 * 约定：即使全部失败也返回 HTTP 200 + 本结构的明细，500 只留给系统性故障。
 *
 * <p>典型用法（逐条独立事务，一条失败不影响已成功条目）：
 * <pre>{@code
 * BatchResult<String> result = BatchResult.of(ids.size());
 * for (String id : ids) {
 *     try {
 *         service.doOne(id);          // 内部 REQUIRES_NEW 或独立 service 调用
 *         result.succeed(id);
 *     } catch (Exception e) {
 *         result.fail(id, "BIZ_ERROR", e.getMessage());
 *     }
 * }
 * return R.ok(result);
 * }</pre>
 *
 * @param <ID> 条目标识类型，通常为 String 或 Long
 */
@ToString
@NoArgsConstructor
@Schema(description = "批量操作结果：部分成功语义")
public class BatchResult<ID> implements Serializable {

	@Serial
	private static final long serialVersionUID = 1L;

	@Setter
	@Schema(description = "成功的条目标识")
	private List<ID> succeeded = new ArrayList<>();

	@Setter
	@Schema(description = "失败条目及逐条原因")
	private List<Failure<ID>> failed = new ArrayList<>();

	/**
	 * 请求条目总数。显式记录而非由 succeeded+failed 推导：
	 * 存在条目被前置校验跳过（既未成功也未产生失败明细）的情况，推导会掩盖这类丢失。
	 */
	@Setter
	@Schema(description = "请求条目总数")
	private int total;

	public List<ID> getSucceeded() {
		return succeeded == null ? Collections.emptyList() : succeeded;
	}

	public List<Failure<ID>> getFailed() {
		return failed == null ? Collections.emptyList() : failed;
	}

	public int getTotal() {
		return total;
	}

	@Schema(description = "成功条数")
	public int getSuccessCount() {
		return getSucceeded().size();
	}

	@Schema(description = "失败条数")
	public int getFailCount() {
		return getFailed().size();
	}

	/**
	 * 是否存在条目既未成功也未记失败——说明批量循环里有静默跳过的分支，属实现缺陷。
	 */
	@Schema(description = "是否有条目未被处理（静默跳过，属实现缺陷）")
	public boolean isUnaccounted() {
		return total > 0 && getSuccessCount() + getFailCount() != total;
	}

	/**
	 * @param total 请求条目总数
	 */
	public static <ID> BatchResult<ID> of(int total) {
		BatchResult<ID> result = new BatchResult<>();
		result.setTotal(total);
		return result;
	}

	public BatchResult<ID> succeed(ID id) {
		succeeded.add(id);
		return this;
	}

	/**
	 * @param code   可判别的业务错误码，供前端按码取文案；不要塞人类可读句子
	 * @param reason 人类可读原因，直接展示给操作者
	 */
	public BatchResult<ID> fail(ID id, String code, String reason) {
		failed.add(new Failure<>(id, code, reason));
		return this;
	}

	@ToString
	@NoArgsConstructor
	@Schema(description = "单条失败明细")
	public static class Failure<ID> implements Serializable {

		@Serial
		private static final long serialVersionUID = 1L;

		@Getter
		@Setter
		@Schema(description = "条目标识")
		private ID id;

		@Getter
		@Setter
		@Schema(description = "业务错误码")
		private String code;

		@Getter
		@Setter
		@Schema(description = "失败原因（可直接展示）")
		private String reason;

		public Failure(ID id, String code, String reason) {
			this.id = id;
			this.code = code;
			this.reason = reason;
		}
	}
}
