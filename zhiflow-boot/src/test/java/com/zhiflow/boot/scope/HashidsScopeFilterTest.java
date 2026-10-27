package com.zhiflow.boot.scope;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.zhiflow.common.core.constant.PathNamespace;
import com.zhiflow.common.core.jackson.HashidsScope;
import com.zhiflow.common.core.jackson.IdCodecHolder;
import com.zhiflow.common.core.util.IdCodec;
import com.zhiflow.mall.common.entity.GoodsSpu;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code hashids.salt} 作用范围还原的回归用例。
 *
 * <h2>为什么必须双向断言</h2>
 * <p>这个缺陷有<b>两个相反的失败方向</b>，只测一边等于没测：
 * <ul>
 * <li>修不够：B 端仍吐 hashid ⇒ 后台「列表能出、点进去查不到」（2026-08-22 实测）；</li>
 * <li>修过头：C 端停止编码 ⇒ H5 已在用 hashid 请求商详，全站商详当场 404。
 *     后者更贵，所以 {@link #portalKeepsEncoding()} 是这组里最重要的一条。</li>
 * </ul>
 *
 * <p>⚠️ 与 {@code SearchIndexRawIdTest} 同理：<b>不装 salt 的话 IdCodec 是 noOp，
 * 修与不修输出完全一样、用例永远绿</b>。所以每条都先装 salt，并配
 * {@link #defaultIsEncodingOn_controlSample()} 作对照样本——它一旦变绿说明判据坏了。
 */
class HashidsScopeFilterTest {

	private static final String SALT = "zhiflow-test-salt-0123456789abcdef0123456789";

	private static final String RAW_ID = "1900000000000000001";

	private static final String PORTAL_PREFIX = "/mallapi";

	private final ObjectMapper mapper = new JsonMapper();

	@BeforeEach
	void installCodec() {
		IdCodecHolder.setInstance(new IdCodec(SALT));
		HashidsScope.setDefaultEnabled(true);
		HashidsScope.clear();
	}

	@AfterEach
	void restore() {
		HashidsScope.clear();
		HashidsScope.setDefaultEnabled(true);
		IdCodecHolder.setInstance(new IdCodec());
	}

	// ------------------------------------------------------------------ 对照样本

	@Test
	@DisplayName("对照样本：不动作用范围时，装了 salt 就会编码（多进程形态的现状）")
	void defaultIsEncodingOn_controlSample() throws Exception {
		assertThat(serializedId())
			.as("对照样本失败 = salt 没装上或 @HashidsId 被移除，本文件其余断言随之失去意义")
			.isNotEqualTo(RAW_ID)
			.matches("^[a-z2-9]{10,}$");
	}

	// ------------------------------------------------------------------ 两个方向

	@Test
	@DisplayName("C 端（/mallapi/**）仍然编码——翻转它会让 H5 全站商详 404")
	void portalKeepsEncoding() throws Exception {
		String id = idSerializedDuring("/mallapi/goodsspu/getById2Optimized");

		assertThat(id).isNotEqualTo(RAW_ID).matches("^[a-z2-9]{10,}$");
	}

	@Test
	@DisplayName("B 端（/mall/**）不再编码——修复前这里是 hashid，导致后台点进去「商品不存在」")
	void adminGetsRawId() throws Exception {
		String id = idSerializedDuring("/mall/goodsspu/page");

		assertThat(id).isEqualTo(RAW_ID);
	}

	@Test
	@DisplayName("其余命名空间（upms / im / auth …）一律按原始 id，与多进程一致")
	void otherNamespacesGetRawId() throws Exception {
		for (String uri : List.of("/upms/user/page", "/api/im/session/list", "/auth/token/page", "/pay/channel/list")) {
			assertThat(idSerializedDuring(uri)).as("uri=%s", uri).isEqualTo(RAW_ID);
		}
	}

	@Test
	@DisplayName("前缀匹配带段边界：/mallapiXXX 不算 C 端")
	void prefixMatchIsSegmentBounded() throws Exception {
		assertThat(idSerializedDuring("/mallapixxx/goodsspu/page")).isEqualTo(RAW_ID);
		assertThat(idSerializedDuring("/mallapi")).isNotEqualTo(RAW_ID);
	}

	// ------------------------------------------------------------------ 线程与装配

	@Test
	@DisplayName("请求结束必须清干净——不清会把判定带给线程池里的下一个请求")
	void scopeIsClearedAfterRequest() throws Exception {
		HashidsScope.setDefaultEnabled(false);
		runThroughFilter("/mallapi/goodsspu/x", chainNoop());

		assertThat(HashidsScope.isEnabled())
			.as("filter 返回后当前线程不应残留 override")
			.isFalse();
	}

	@Test
	@DisplayName("非请求线程（定时任务 / CDC sink）落在进程级默认=关，与多进程一致")
	void nonRequestThreadsUseProcessDefault() throws Exception {
		HashidsScope.setDefaultEnabled(false);

		assertThat(serializedId()).isEqualTo(RAW_ID);
	}

	@Test
	@DisplayName("认领 mall-api 命名空间：按 base-package 认，不按前缀字面量猜")
	void resolvesPortalPrefixByBasePackage() {
		assertThat(HashidsScopeConfiguration.resolvePortalPrefix(List.of(namespace("/mall",
				"com.zhiflow.mall.admin.controller"),
				namespace("/mallapi", "com.zhiflow.mall.api.controller"))))
			.isEqualTo("/mallapi");
	}

	@Test
	@DisplayName("认不出 mall-api 时返回 null——调用方据此选择「什么都不做」而不是翻转 C 端")
	void resolveReturnsNullWhenPortalAbsent() {
		assertThat(HashidsScopeConfiguration.resolvePortalPrefix(
				List.of(namespace("/mall", "com.zhiflow.mall.admin.controller"))))
			.isNull();
		assertThat(HashidsScopeConfiguration.resolvePortalPrefix(null)).isNull();
	}

	// ------------------------------------------------------------------ helpers

	/** 在「filter 处理该 uri 的过程中」序列化一个 GoodsSpu，取出它的 id 形态。 */
	private String idSerializedDuring(String uri) throws Exception {
		HashidsScope.setDefaultEnabled(false);
		String[] captured = new String[1];
		runThroughFilter(uri, (req, res) -> {
			try {
				captured[0] = serializedId();
			}
			catch (Exception e) {
				throw new IllegalStateException(e);
			}
		});
		return captured[0];
	}

	private void runThroughFilter(String uri, FilterChain chain) throws Exception {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", uri);
		request.setRequestURI(uri);
		new HashidsScopeFilter(PORTAL_PREFIX).doFilter(request, new MockHttpServletResponse(), chain);
	}

	private String serializedId() throws Exception {
		GoodsSpu spu = new GoodsSpu();
		spu.setId(RAW_ID);
		spu.setName("日本版 Xofluza巴洛沙韦玛波西酯10片");
		JsonNode doc = mapper.readTree(mapper.writeValueAsString(spu));
		return doc.path("id").asString();
	}

	private static FilterChain chainNoop() {
		return (req, res) -> {
		};
	}

	private static PathNamespace namespace(String value, String basePackage) {
		PathNamespace ns = new PathNamespace();
		ns.setValue(value);
		ns.setBasePackage(basePackage);
		return ns;
	}

}
