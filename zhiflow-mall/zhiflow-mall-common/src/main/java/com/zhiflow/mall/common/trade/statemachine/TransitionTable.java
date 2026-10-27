package com.zhiflow.mall.common.trade.statemachine;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 跳转表：全系统唯一真源。代码、后台按钮（14.2）、穷举测试（14.8）都从这里读。
 *
 * <p>同一 (from, event) 只能有一条规则——两条就意味着状态机不确定，构建期直接失败。
 * {@link #idempotent} 登记「重复到达即无操作」的组合（如已取消再取消、已付再收到同一笔支付成功），
 * 其余表外组合一律拒绝并计数。
 */
public final class TransitionTable<S extends Enum<S>, E extends Enum<E>, T, C> {

	private final Class<S> stateType;

	private final Class<E> eventType;

	private final Map<S, Map<E, Rule<S, E, T, C>>> index;

	private final List<Rule<S, E, T, C>> rules;

	private final Map<S, Set<E>> idempotent;

	private TransitionTable(Class<S> stateType, Class<E> eventType, List<Rule<S, E, T, C>> rules,
			Map<S, Set<E>> idempotent) {
		this.stateType = stateType;
		this.eventType = eventType;
		this.rules = List.copyOf(rules);
		this.idempotent = idempotent;
		Map<S, Map<E, Rule<S, E, T, C>>> idx = new EnumMap<>(stateType);
		for (Rule<S, E, T, C> r : rules) {
			for (S s : r.from()) {
				Rule<S, E, T, C> prev = idx.computeIfAbsent(s, k -> new EnumMap<>(eventType)).put(r.event(), r);
				if (prev != null) {
					throw new IllegalStateException("跳转表冲突：(" + s + ", " + r.event() + ") 同时命中规则 "
							+ prev.id() + " 与 " + r.id());
				}
			}
		}
		for (Map.Entry<S, Set<E>> e : idempotent.entrySet()) {
			for (E ev : e.getValue()) {
				if (idx.getOrDefault(e.getKey(), Map.of()).containsKey(ev)) {
					throw new IllegalStateException("(" + e.getKey() + ", " + ev + ") 既是规则又登记为幂等重复");
				}
			}
		}
		this.index = idx;
	}

	public Rule<S, E, T, C> find(S from, E event) {
		Map<E, Rule<S, E, T, C>> byEvent = index.get(from);
		return byEvent == null ? null : byEvent.get(event);
	}

	public boolean isIdempotentRepeat(S from, E event) {
		return idempotent.getOrDefault(from, Set.of()).contains(event);
	}

	public List<Rule<S, E, T, C>> rules() {
		return rules;
	}

	public Class<S> stateType() {
		return stateType;
	}

	public Class<E> eventType() {
		return eventType;
	}

	/** 从某状态出发、允许某触发方的规则（14.2 操作下发用）。 */
	public List<Rule<S, E, T, C>> rulesFrom(S from, Operator operator) {
		List<Rule<S, E, T, C>> out = new ArrayList<>();
		for (Rule<S, E, T, C> r : rules) {
			if (r.from().contains(from) && (operator == null || r.operators().contains(operator))) {
				out.add(r);
			}
		}
		return Collections.unmodifiableList(out);
	}

	public static <S extends Enum<S>, E extends Enum<E>, T, C> Builder<S, E, T, C> builder(Class<S> stateType,
			Class<E> eventType) {
		return new Builder<>(stateType, eventType);
	}

	public static final class Builder<S extends Enum<S>, E extends Enum<E>, T, C> {

		private final Class<S> stateType;

		private final Class<E> eventType;

		private final List<Rule<S, E, T, C>> rules = new ArrayList<>();

		private final Map<S, Set<E>> idempotent = new HashMap<>();

		private Builder(Class<S> stateType, Class<E> eventType) {
			this.stateType = stateType;
			this.eventType = eventType;
		}

		public RuleBuilder rule(String id) {
			return new RuleBuilder(id);
		}

		/** 登记「重复到达即无操作」：这些组合返回 NOOP，不计非法跳转。 */
		public Builder<S, E, T, C> idempotent(S from, E event) {
			idempotent.computeIfAbsent(from, k -> EnumSet.noneOf(eventType)).add(event);
			return this;
		}

		public TransitionTable<S, E, T, C> build() {
			return new TransitionTable<>(stateType, eventType, rules, idempotent);
		}

		public final class RuleBuilder {

			private final String id;

			private Set<S> from;

			private E event;

			private Target<S, T, C> target;

			private Set<S> possibleTargets;

			private Guard<T, C> guard = Guard.none();

			private Set<Operator> operators = EnumSet.allOf(Operator.class);

			private RuleBuilder(String id) {
				this.id = id;
			}

			@SafeVarargs
			public final RuleBuilder from(S first, S... more) {
				this.from = EnumSet.of(first, more);
				return this;
			}

			public RuleBuilder on(E event) {
				this.event = event;
				return this;
			}

			public RuleBuilder to(S fixed) {
				this.target = (t, c) -> fixed;
				this.possibleTargets = EnumSet.of(fixed);
				return this;
			}

			@SafeVarargs
			public final RuleBuilder to(Target<S, T, C> resolver, S first, S... possible) {
				this.target = resolver;
				this.possibleTargets = EnumSet.of(first, possible);
				return this;
			}

			public RuleBuilder when(Guard<T, C> guard) {
				this.guard = guard;
				return this;
			}

			public RuleBuilder by(Operator first, Operator... more) {
				this.operators = EnumSet.of(first, more);
				return this;
			}

			public Builder<S, E, T, C> add() {
				if (from == null || event == null || target == null) {
					throw new IllegalStateException("规则 " + id + " 缺 from / on / to");
				}
				rules.add(new Rule<>(id, from, event, target, possibleTargets, guard, operators));
				return Builder.this;
			}
		}
	}
}
