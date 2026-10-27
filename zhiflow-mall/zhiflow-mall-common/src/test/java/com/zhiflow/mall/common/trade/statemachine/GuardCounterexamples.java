package com.zhiflow.mall.common.trade.statemachine;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * guard 反例表（CSV）的公共读取：第一列是「规则 id + 空格 + 说明」。
 * 三台状态机各自的 GuardTest 用它断言「跳转表里每条规则至少有一个反例」——新加规则忘了写反例，门禁就红。
 */
public final class GuardCounterexamples {

	private GuardCounterexamples() {
	}

	/** CSV 里出现过的规则 id（跳过表头）。 */
	public static Set<String> ruleIds(String resource) {
		try (InputStream in = GuardCounterexamples.class.getResourceAsStream(resource)) {
			if (in == null) {
				throw new IllegalStateException("缺反例表 " + resource);
			}
			return new String(in.readAllBytes(), StandardCharsets.UTF_8).lines().skip(1)
					.filter(l -> !l.isBlank())
					.map(l -> l.substring(0, l.indexOf('|')))
					.map(c -> c.contains(" ") ? c.substring(0, c.indexOf(' ')) : c)
					.collect(Collectors.toSet());
		}
		catch (IOException e) {
			throw new IllegalStateException(e);
		}
	}
}
