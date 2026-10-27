package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Business code depends on the Java contract interface, never on the transport implementation.
 *
 * <p>HTTP/gRPC wiring is intentionally allowed under {@code config/} and {@code grpc/}; local
 * adapters and resilience decorators are also boundary code. Everything else must not import a
 * RestClient/ManagedChannel or a concrete generated gRPC client. This keeps the transport switch
 * in {@code TransportRouterFactory} effective for real business call paths.</p>
 */
class ContractTransportIsolationGateTest {

	private static final Path REPO_ROOT = Path.of("..").toAbsolutePath().normalize();

	private static final List<String> BUSINESS_MODULES = List.of("zhiflow-mall", "zhiflow-upms",
		"zhiflow-im", "zhiflow-weixin", "zhiflow-live", "zhiflow-pay", "zhiflow-auth");

	private static final Pattern TRANSPORT_IMPLEMENTATION = Pattern.compile(
			"\\b(?:HttpInterfaceClientFactory|RestClient|RestTemplate|WebClient|ManagedChannel|ClientCall|"
					+ "[A-Z][A-Za-z0-9]*GrpcClientApi)\\b");

	@Test
	@DisplayName("自检：扫描到了业务源码和传输边界源码")
	void scanCoversBusinessAndTransportBoundaries() throws IOException {
		List<Path> files = businessSources();
		assertTrue(files.size() > 500, "业务源码扫描面异常偏小，门禁可能没有覆盖真实模块：" + files.size());
		long boundaryFiles = files.stream().filter(this::isTransportBoundary).count();
		assertTrue(boundaryFiles > 20, "传输边界源码对照样本过少，排除规则或扫描根目录可能失效：" + boundaryFiles);
	}

	@Test
	@DisplayName("业务类不得直接依赖 HTTP/gRPC 传输实现")
	void businessCodeUsesContractInterfacesOnly() throws IOException {
		List<String> offenders = new ArrayList<>();
		for (Path file : businessSources()) {
			if (isTransportBoundary(file)) {
				continue;
			}
			String source = JavaSource.stripComments(read(file));
			var matcher = TRANSPORT_IMPLEMENTATION.matcher(source);
			while (matcher.find()) {
				offenders.add("  " + REPO_ROOT.relativize(file) + " -> " + matcher.group());
			}
		}
		assertFalse(offenders.size() > 0, () -> "业务代码绕过统一 Api/Router 直接依赖传输实现：\n"
				+ String.join("\n", offenders)
				+ "\n请把 HTTP/gRPC 实现放回 config/grpc 边界，业务只注入 Java 契约接口。");
	}

	private List<Path> businessSources() throws IOException {
		List<Path> files = new ArrayList<>();
		for (String module : BUSINESS_MODULES) {
			Path root = REPO_ROOT.resolve(module);
			if (!Files.isDirectory(root)) {
				continue;
			}
			try (Stream<Path> paths = Files.walk(root)) {
				paths.filter(path -> path.toString().endsWith(".java"))
					.filter(path -> path.toString().contains("/src/main/java/"))
					.filter(path -> !path.toString().contains("/target/"))
					.filter(path -> !path.toString().contains("/.claude/"))
					.forEach(files::add);
			}
		}
		return files;
	}

	private boolean isTransportBoundary(Path file) {
		String normalized = file.toString().replace('\\', '/');
		return normalized.contains("/config/") || normalized.contains("/grpc/")
				|| normalized.contains("/local/") || normalized.contains("/resilience/")
				// 高德是外部第三方 HTTP provider，不是内部服务间调用；它必须继续走 HTTP。
				|| normalized.endsWith("/GaodemapService.java");
	}

	private static String read(Path file) {
		try {
			return Files.readString(file, StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			throw new UncheckedIOException(ex);
		}
	}

}
