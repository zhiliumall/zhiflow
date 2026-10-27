package com.zhiflow.mall.api.projection;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * [P1] 逐字段递归比较两棵对象树的测试工具。
 *
 * <h2>为什么不直接比序列化后的 JSON</h2>
 * <p>契约 DTO 上的 {@code prescription} 是 {@code @JsonProperty(WRITE_ONLY)}：
 * 只在反序列化时接收、<b>序列化时不输出</b>。JSON 里能看到的只有派生出来的
 * {@code prescriptionLabel}，而 {@code PrescriptionConstants#labelOf} 是<b>多对一</b>的
 * （{@code null} 与任何未登记编码都是「未标注」）—— 丢失前后落在同一标签上时，
 * 两份 JSON 逐字节相同而对象字段不同。这是本仓反复在防的假绿形状，
 * 实证见 {@code AgentGoodsProjectionTest#jsonComparisonIsBlindToPrescription}。
 *
 * <h2>{@link #visited()} 是给判据自检用的</h2>
 * <p>「没报差异」有两种可能：真的一致，或者<b>递归根本没走进去</b>。
 * 后者会让所有对照测试静默变绿。所以本工具记下走过的每一条字段路径
 * （列表下标归一成 {@code [*]}），调用方可以断言关键字段确实被比较过。
 */
public final class FieldDiff {

	private final List<String> diffs = new ArrayList<>();

	private final Set<String> visited = new LinkedHashSet<>();

	private FieldDiff() {
	}

	public static FieldDiff between(String root, Object expected, Object actual) {
		FieldDiff out = new FieldDiff();
		out.walk(root, expected, actual);
		return out;
	}

	public List<String> diffs() {
		return this.diffs;
	}

	public Set<String> visited() {
		return this.visited;
	}

	public String report() {
		return String.join("\n", this.diffs);
	}

	private void walk(String path, Object expected, Object actual) {
		if (expected == null && actual == null) {
			return;
		}
		if (expected == null || actual == null) {
			this.diffs.add(path + ": expected=" + expected + " actual=" + actual);
			return;
		}
		if (expected instanceof List<?> le && actual instanceof List<?> la) {
			if (le.size() != la.size()) {
				this.diffs.add(path + ": 元素个数 expected=" + le.size() + " actual=" + la.size());
				return;
			}
			for (int i = 0; i < le.size(); i++) {
				walk(path + "[" + i + "]", le.get(i), la.get(i));
			}
			return;
		}
		if (expected.getClass().getName().startsWith("com.zhiflow.")) {
			if (!expected.getClass().equals(actual.getClass())) {
				this.diffs.add(path + ": 类型不同 expected=" + expected.getClass() + " actual=" + actual.getClass());
				return;
			}
			for (Field field : expected.getClass().getDeclaredFields()) {
				if (field.isSynthetic() || Modifier.isStatic(field.getModifiers())) {
					continue;
				}
				field.setAccessible(true);
				String child = path + "." + field.getName();
				this.visited.add(child.replaceAll("\\[\\d+]", "[*]"));
				try {
					walk(child, field.get(expected), field.get(actual));
				}
				catch (IllegalAccessException ex) {
					throw new IllegalStateException("读不到字段 " + child, ex);
				}
			}
			return;
		}
		if (!Objects.equals(expected, actual)) {
			this.diffs.add(path + ": expected=" + expected + " actual=" + actual);
		}
	}

}
