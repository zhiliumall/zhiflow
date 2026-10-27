package com.zhiflow.mall.common.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * 删除影响面预览。
 *
 * <p>删除类操作的确认弹窗靠它把「删了会连带影响什么」说清楚：优惠券模板已经发出去 328 张、
 * 分类下还挂着 12 个商品。只读，不参与任何写路径。
 *
 * <p><b>count 为 0 的项不会出现在 items 里</b>——无关联时前端拿到空数组，直接退回朴素的
 * 「确定删除吗？」文案，不必逐项判空。
 *
 * <p><b>items 之间互不重叠</b>，因此 {@code total} 是真实受影响行数而不是一个会重复计数的假数。
 * 需要「已发放张数」这类合计值时由调用方自行相加。
 */
@Data
@Schema(description = "删除影响面")
public class ImpactVO {

	@Schema(description = "影响项，count 为 0 的已剔除")
	private List<Item> items = new ArrayList<>();

	@Schema(description = "各影响项之和")
	private long total;

	@Data
	@NoArgsConstructor
	@AllArgsConstructor
	@Schema(description = "单个影响项")
	public static class Item {

		@Schema(description = "展示文案，如「已发放优惠券」")
		private String label;

		@Schema(description = "关联数量")
		private long count;
	}

	/**
	 * 按声明顺序收集影响项，剔除 0 值并汇总。
	 *
	 * @param candidates 候选项，允许含 count 为 0 的项
	 * @return 只含非 0 项的影响面
	 */
	public static ImpactVO of(Item... candidates) {
		ImpactVO vo = new ImpactVO();
		for (Item candidate : candidates) {
			if (candidate != null && candidate.getCount() > 0) {
				vo.items.add(candidate);
				vo.total += candidate.getCount();
			}
		}
		return vo;
	}

	/** 语法糖，让各实体的组装代码读起来就是「标签 → 计数」 */
	public static Item item(String label, long count) {
		return new Item(label, count);
	}
}
