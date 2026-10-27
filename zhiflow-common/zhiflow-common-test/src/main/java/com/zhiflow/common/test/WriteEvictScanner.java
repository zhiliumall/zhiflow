/*
 * Copyright (C) 2026 gaipharma.com
 * SPDX-License-Identifier: AGPL-3.0-only
 */
package com.zhiflow.common.test;

import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.Handle;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.SpringAsmInfo;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Predicate;
import java.util.regex.Pattern;

/**
 * 「每个写某几张表的入口都清了缓存」的静态扫描（读某个包下编译出的主代码字节码，不启动 Spring、不连库）。
 * mall-admin（后台改商品清用户端缓存）与 mall-api（用户写购物车清购物车缓存）共用这一份。
 *
 * <p>判据：
 * <ol>
 * <li><b>写调用</b>：调用了「写这几张表的 mapper / service」上名字像写操作的方法；或者在这几张表的 service 实现类里，
 *     调用了框架（ServiceImpl / BaseMapper）上名字像写操作的方法（{@code super.updateById}、{@code baseMapper.update}）。</li>
 * <li><b>清缓存的方法</b>：直接满足 {@code evicts} 判据的方法，以及（沿调用关系、接口到实现、lambda）会走到它们的方法。</li>
 * <li>每个写调用必须满足其一：被调用的那个方法本身会清缓存；所在方法会清缓存；所在方法的所有调用方都满足这一条
 *     （私有帮手方法、lambda 靠调用方兜住）；或者在豁免名单里（必须写明理由）。</li>
 * </ol>
 * 只认「调用过」，不认「在写之后调用」——先后顺序靠代码评审和 {@code AfterCommitCacheEviction} 的提交后语义。
 */
public final class WriteEvictScanner {

	/** 名字像写操作的方法。 */
	public static final Pattern WRITE_NAME = Pattern.compile("^(save|update|remove|delete|insert|physical|increase|decrease|shelf).*");

	private final Map<String, ClassInfo> classes = new HashMap<>();

	private final Map<MethodRef, MethodInfo> methods = new LinkedHashMap<>();

	private final Map<MethodRef, Set<MethodRef>> callers = new HashMap<>();

	private WriteEvictScanner() {
	}

	/** 方法引用：owner 是 JVM 内部类名（斜杠分隔）。 */
	public record MethodRef(String owner, String name, String desc) {

		public String simple() {
			return owner.substring(owner.lastIndexOf('/') + 1) + "#" + name;
		}
	}

	/** 一处调用。 */
	public record Call(String owner, String name, String desc) {
	}

	/** 一个方法里出现的调用与字符串常量。 */
	public static final class MethodInfo {

		final MethodRef ref;

		final List<Call> calls = new ArrayList<>();

		final Set<String> strings = new HashSet<>();

		MethodInfo(MethodRef ref) {
			this.ref = ref;
		}

		/** 直接调用了 owner（内部类名）上名为 name 的方法，且方法体里出现过全部这些字符串常量。 */
		public boolean invokes(String owner, String name, String... constants) {
			return calls.stream().anyMatch(c -> c.owner().equals(owner) && c.name().equals(name))
					&& strings.containsAll(List.of(constants));
		}
	}

	private record ClassInfo(String name, String superName, List<String> interfaces, boolean isInterface) {
	}

	/**
	 * 扫描某个包（含子包）下的全部主代码类。
	 *
	 * @param basePackage 如 {@code com.zhiflow.mall.admin}
	 */
	public static WriteEvictScanner scan(String basePackage) {
		WriteEvictScanner scanner = new WriteEvictScanner();
		String pattern = "classpath*:" + basePackage.replace('.', '/') + "/**/*.class";
		try {
			for (Resource resource : new PathMatchingResourcePatternResolver().getResources(pattern)) {
				// 只看主代码：测试类不是写入口
				if (resource.getURL().toString().contains("/test-classes/")) {
					continue;
				}
				try (InputStream in = resource.getInputStream()) {
					scanner.read(new ClassReader(in));
				}
			}
		}
		catch (IOException e) {
			throw new UncheckedIOException(e);
		}
		scanner.link();
		return scanner;
	}

	private void read(ClassReader reader) {
		reader.accept(new ClassVisitor(SpringAsmInfo.ASM_VERSION) {
			private String className;

			@Override
			public void visit(int version, int access, String name, String signature, String superName, String[] interfaces) {
				className = name;
				classes.put(name, new ClassInfo(name, superName, List.of(interfaces), (access & Opcodes.ACC_INTERFACE) != 0));
			}

			@Override
			public MethodVisitor visitMethod(int access, String name, String descriptor, String signature, String[] exceptions) {
				MethodInfo info = new MethodInfo(new MethodRef(className, name, descriptor));
				methods.put(info.ref, info);
				return new MethodVisitor(SpringAsmInfo.ASM_VERSION) {
					@Override
					public void visitMethodInsn(int opcode, String owner, String mName, String mDesc, boolean isInterface) {
						info.calls.add(new Call(owner, mName, mDesc));
					}

					@Override
					public void visitInvokeDynamicInsn(String iName, String iDesc, Handle bootstrap, Object... args) {
						for (Object arg : args) {
							if (arg instanceof Handle handle) {
								info.calls.add(new Call(handle.getOwner(), handle.getName(), handle.getDesc()));
							}
						}
					}

					@Override
					public void visitLdcInsn(Object value) {
						if (value instanceof String s) {
							info.strings.add(s);
						}
					}
				};
			}
		}, ClassReader.SKIP_DEBUG | ClassReader.SKIP_FRAMES);
	}

	private void link() {
		for (MethodInfo info : methods.values()) {
			for (Call call : info.calls) {
				for (MethodRef target : resolve(call)) {
					callers.computeIfAbsent(target, k -> new HashSet<>()).add(info.ref);
				}
			}
		}
	}

	/** 调用落到被扫描的类里的哪些方法（接口 / 父类上的调用解析到声明了同名同签名方法的实现类）。 */
	private List<MethodRef> resolve(Call call) {
		List<MethodRef> targets = new ArrayList<>();
		if (!classes.containsKey(call.owner())) {
			return targets;
		}
		for (ClassInfo c : classes.values()) {
			MethodRef ref = new MethodRef(c.name(), call.name(), call.desc());
			if (methods.containsKey(ref) && isSubtype(c.name(), call.owner())) {
				targets.add(ref);
			}
		}
		return targets;
	}

	private boolean isSubtype(String type, String ancestor) {
		if (type == null) {
			return false;
		}
		if (type.equals(ancestor)) {
			return true;
		}
		ClassInfo info = classes.get(type);
		if (info == null) {
			return false;
		}
		if (isSubtype(info.superName(), ancestor)) {
			return true;
		}
		return info.interfaces().stream().anyMatch(i -> isSubtype(i, ancestor));
	}

	/**
	 * 找出没有被清缓存兜住的写调用。
	 *
	 * @param writeOwners    写这几张表的 mapper / service 接口与实现类（内部类名）
	 * @param writeImpls     这几张表的 service 实现类：它们对框架方法的写调用也算
	 * @param evicts         「这个方法直接清了缓存」的判据
	 * @param exempt         豁免的方法（类简名#方法名），值是理由
	 * @return 违规清单（「所在方法 → 写调用」），空表示全部兜住
	 */
	public Set<String> uncovered(Set<String> writeOwners, Set<String> writeImpls, Predicate<MethodInfo> evicts,
								 Map<String, String> exempt) {
		Set<MethodRef> evicting = evictingClosure(evicts);
		Set<String> violations = new TreeSet<>();
		for (MethodInfo info : methods.values()) {
			for (Call call : info.calls) {
				if (!isWrite(info.ref, call, writeOwners, writeImpls)) {
					continue;
				}
				boolean calleeEvicts = resolve(call).stream()
						.anyMatch(t -> evicting.contains(t) || exempt.containsKey(t.simple()));
				if (calleeEvicts || covered(info.ref, evicting, exempt, new HashSet<>())) {
					continue;
				}
				violations.add(info.ref.simple() + " → " + call.owner().substring(call.owner().lastIndexOf('/') + 1)
						+ "#" + call.name());
			}
		}
		return violations;
	}

	/** 写调用一共有多少处（用来确认扫描没有扫空）。 */
	public int countWrites(Set<String> writeOwners, Set<String> writeImpls) {
		int n = 0;
		for (MethodInfo info : methods.values()) {
			for (Call call : info.calls) {
				if (isWrite(info.ref, call, writeOwners, writeImpls)) {
					n++;
				}
			}
		}
		return n;
	}

	private boolean isWrite(MethodRef in, Call call, Set<String> writeOwners, Set<String> writeImpls) {
		if (!WRITE_NAME.matcher(call.name()).matches()) {
			return false;
		}
		if (writeOwners.contains(call.owner())) {
			return true;
		}
		// 实现类里对 MyBatis-Plus 框架方法的调用：super.updateById、baseMapper.update ……
		return writeImpls.contains(in.owner()) && call.owner().startsWith("com/baomidou/mybatisplus/");
	}

	private boolean covered(MethodRef method, Set<MethodRef> evicting, Map<String, String> exempt, Set<MethodRef> path) {
		if (evicting.contains(method) || exempt.containsKey(method.simple())) {
			return true;
		}
		Set<MethodRef> from = callers.getOrDefault(method, Set.of());
		if (from.isEmpty() || !path.add(method)) {
			// 没有调用方（它就是入口）或者绕回了自己：这条路径没有兜底
			return false;
		}
		try {
			for (MethodRef caller : from) {
				if (!covered(caller, evicting, exempt, path)) {
					return false;
				}
			}
			return true;
		}
		finally {
			path.remove(method);
		}
	}

	private Set<MethodRef> evictingClosure(Predicate<MethodInfo> evicts) {
		Set<MethodRef> evicting = new HashSet<>();
		for (MethodInfo info : methods.values()) {
			if (evicts.test(info)) {
				evicting.add(info.ref);
			}
		}
		boolean changed = true;
		while (changed) {
			changed = false;
			for (MethodInfo info : methods.values()) {
				if (evicting.contains(info.ref)) {
					continue;
				}
				for (Call call : info.calls) {
					if (resolve(call).stream().anyMatch(evicting::contains)) {
						evicting.add(info.ref);
						changed = true;
						break;
					}
				}
			}
		}
		return evicting;
	}

	/** 内部类名，如 {@code com.zhiflow.mall.admin.mapper.GoodsSpuMapper} → {@code com/zhiflow/mall/admin/mapper/GoodsSpuMapper}。 */
	public static String internal(Class<?> type) {
		return type.getName().replace('.', '/');
	}
}
