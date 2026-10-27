package com.zhiflow.mall.api.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 门禁：<b>不准对「重写了 save / updateById 的 Service」调用 {@code saveOrUpdate}</b>。
 *
 * <h2>为什么值得一道专门的门禁</h2>
 * <p>MyBatis-Plus 3.5.16 的 {@code AbstractRepository.saveOrUpdate(T)} 只有一行（字节码实证）：
 * <pre>return getBaseMapper().insertOrUpdate(entity);</pre>
 * 它<b>直接打到 mapper</b>，既不转调 {@code this.save()} / {@code this.updateById()}，
 * 也不经过 Spring 代理。旧版 MP 是转调的，所以这是一次<b>升级带来的静默回归</b>：
 * 源码没动、编译没错、单测全绿、接口照常返回 code 0，只有业务效果没了。
 *
 * <p>2026-09-08 实测抓到两处实例：
 * <ol>
 *   <li><b>C 端地址保存</b>（{@code UserAddressController.save} → {@code saveOrUpdate}）——
 *       {@code UserAddressServiceImpl} 重写的 {@code setIsDefault}（清同用户旧默认）与
 *       {@code geocodeIfMissing}（回填经纬度）双双失效。实测：把 A 存成默认后 B 仍是默认，
 *       一个用户同时有多条 {@code is_default='1'}；{@code pg_stat_statements} 里也确实
 *       找不到那条 {@code set is_default=0 ... and is_default=1}。</li>
 *   <li><b>后台商品保存写 SKU 库存</b>（{@code GoodsSpuServiceImpl} → {@code goodsSkuService.saveOrUpdate}）——
 *       {@code GoodsSkuServiceImpl.updateById} 上的
 *       {@code @DistributedLock(name="GoodsSku", FAIL_FAST)} 完全没生效，
 *       两个后台同时编辑同一 SKU 会交叉写库存。</li>
 * </ol>
 *
 * <p>这类问题有三个特点，叠在一起就是「必然复发」：
 * <ol>
 *   <li><b>看不出来</b>——调用处一个字都没错，{@code saveOrUpdate} 是最自然的写法；</li>
 *   <li><b>测不出来</b>——Mockito 打桩 service 时桩的就是 {@code saveOrUpdate} 本身，
 *       真实实现里那层重写压根不参与；</li>
 *   <li><b>只有跑到生产、且盯着数据看</b>才会发现，而且症状是「少做了一件事」，不报错。</li>
 * </ol>
 *
 * <h2>规则</h2>
 * <p>扫全部 {@code *ServiceImpl.java}，凡是重写了 {@code save(...)} 或 {@code updateById(...)} 的，
 * 记下它对应的 Service 接口名；再扫全部 {@code .java}，凡出现
 * {@code <该接口的常规变量名>.saveOrUpdate(} 就判失败。
 *
 * <p><b>放行</b>：该 impl 自己也重写了 {@code saveOrUpdate}（说明作者知道这件事、已经把分支写回来了，
 * 例如 {@code UserAddressServiceImpl}）。
 *
 * <h2>踩到了怎么改</h2>
 * <ul>
 *   <li>重写只是普通方法（没挂 AOP 注解）→ 在 impl 里重写 {@code saveOrUpdate}，
 *       转调 {@code this.updateById()} / {@code this.save()}；</li>
 *   <li>重写挂了 {@code @DistributedLock} / {@code @Transactional} 之类的 AOP 注解 →
 *       <b>必须在调用方改</b>，经注入的 bean 代理显式调 {@code updateById()} / {@code save()}。
 *       impl 内部自调用会丢代理，注解照样不生效。</li>
 * </ul>
 */
class SaveOrUpdateBypassesOverrideTest {

	/** 只扫业务模块；测试目录与生成代码不在范围内 */
	private static final List<String> SCAN_ROOTS = List.of(
			"zhiflow-mall/zhiflow-mall-api/src/main/java",
			"zhiflow-mall/zhiflow-mall-admin/src/main/java",
			"zhiflow-mall/zhiflow-mall-common/src/main/java");

	private static final Pattern OVERRIDE_SAVE =
			Pattern.compile("(?m)^\\s*public\\s+boolean\\s+save\\s*\\(");
	private static final Pattern OVERRIDE_UPDATE_BY_ID =
			Pattern.compile("(?m)^\\s*public\\s+boolean\\s+updateById\\s*\\(");
	private static final Pattern OVERRIDE_SAVE_OR_UPDATE =
			Pattern.compile("(?m)^\\s*public\\s+boolean\\s+saveOrUpdate\\s*\\(");
	private static final Pattern SAVE_OR_UPDATE_CALL =
			Pattern.compile("(\\w+)\\s*\\.\\s*saveOrUpdate\\s*\\(");

	@Test
	@DisplayName("重写了 save/updateById 的 Service 不得被 saveOrUpdate 调用（MP 3.5.16 会绕过重写）")
	void saveOrUpdateMustNotBypassOverriddenMethods() {
		Path repoRoot = locateRepoRoot();

		// 模块 → 「危险的 service 变量名」集合。按模块分是因为 api / admin 各有一套同名 Service，
		// 只有 api 那套重写了，不能因为重名把 admin 也一起判死。
		Map<String, Set<String>> risky = new HashMap<>();
		Map<String, String> riskyReason = new HashMap<>();

		for (Path file : javaFiles(repoRoot)) {
			String name = file.getFileName().toString();
			if (!name.endsWith("ServiceImpl.java")) {
				continue;
			}
			String src = read(file);
			if (!src.contains("extends ServiceImpl")) {
				continue;
			}
			boolean overridesSave = OVERRIDE_SAVE.matcher(src).find();
			boolean overridesUpdate = OVERRIDE_UPDATE_BY_ID.matcher(src).find();
			if (!overridesSave && !overridesUpdate) {
				continue;
			}
			// 已经自己把 saveOrUpdate 的分支写回来了 → 放行
			if (OVERRIDE_SAVE_OR_UPDATE.matcher(src).find()) {
				continue;
			}
			String iface = name.replace("ServiceImpl.java", "Service");
			String var = Character.toLowerCase(iface.charAt(0)) + iface.substring(1);
			String module = moduleOf(file);
			risky.computeIfAbsent(module, k -> new HashSet<>()).add(var);
			riskyReason.put(module + "#" + var,
					iface + "（重写了 " + (overridesSave ? "save " : "") + (overridesUpdate ? "updateById" : "") + "）");
		}

		List<String> violations = new ArrayList<>();
		for (Path file : javaFiles(repoRoot)) {
			if (!file.getFileName().toString().endsWith(".java")) {
				continue;
			}
			String module = moduleOf(file);
			Set<String> vars = risky.get(module);
			if (vars == null || vars.isEmpty()) {
				continue;
			}
			String src = read(file);
			Matcher m = SAVE_OR_UPDATE_CALL.matcher(src);
			while (m.find()) {
				String var = m.group(1);
				if (!vars.contains(var)) {
					continue;
				}
				violations.add(String.format(
						"%s 调用了 %s.saveOrUpdate(...)，但 %s —— MP 3.5.16 的 saveOrUpdate 直接打 mapper，"
								+ "这些重写不会执行。改成显式 updateById()/save()，或在 impl 里重写 saveOrUpdate。",
						repoRoot.relativize(file), var, riskyReason.get(module + "#" + var)));
			}
		}

		assertTrue(violations.isEmpty(),
				"发现 " + violations.size() + " 处 saveOrUpdate 绕过重写：\n  - " + String.join("\n  - ", violations));
	}

	private static String moduleOf(Path file) {
		String p = file.toString().replace('\\', '/');
		if (p.contains("/zhiflow-mall-api/")) {
			return "api";
		}
		if (p.contains("/zhiflow-mall-admin/")) {
			return "admin";
		}
		return "common";
	}

	private static List<Path> javaFiles(Path repoRoot) {
		List<Path> all = new ArrayList<>();
		for (String root : SCAN_ROOTS) {
			Path dir = repoRoot.resolve(root);
			if (!Files.isDirectory(dir)) {
				continue;
			}
			try (Stream<Path> s = Files.walk(dir)) {
				s.filter(Files::isRegularFile)
						.filter(f -> f.getFileName().toString().endsWith(".java"))
						.forEach(all::add);
			} catch (IOException e) {
				throw new UncheckedIOException(e);
			}
		}
		return all;
	}

	private static String read(Path p) {
		try {
			return Files.readString(p);
		} catch (IOException e) {
			throw new UncheckedIOException(e);
		}
	}

	/** 从当前工作目录（多模块下可能是模块目录）往上找到含 zhiflow-mall 的仓库根 */
	private static Path locateRepoRoot() {
		Path dir = Paths.get("").toAbsolutePath();
		for (int i = 0; i < 6 && dir != null; i++) {
			if (Files.isDirectory(dir.resolve("zhiflow-mall/zhiflow-mall-api"))) {
				return dir;
			}
			dir = dir.getParent();
		}
		throw new IllegalStateException("找不到仓库根（期望某级父目录下有 zhiflow-mall/zhiflow-mall-api）");
	}
}
