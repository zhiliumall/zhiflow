package com.zhiflow.common.security.handler;
import tools.jackson.databind.json.JsonMapper;
import com.zhiflow.common.core.exception.BizBadRequestException;
import com.zhiflow.common.core.exception.BizException;
import com.zhiflow.common.core.util.R;
import tools.jackson.databind.ObjectMapper;
import jakarta.validation.ConstraintViolationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link GlobalBizExceptionHandler} 的状态码契约。
 *
 * <p><b>为什么必须靠单测而不是探测运行中的服务</b>：两条原验收命令
 * （匿名 {@code POST /mallapi/file/upload} 不带 file、匿名 {@code GET /mallapi/reward-cycle/page}）
 * 在 2026-08-17 给这两个端点补上方法级 {@code @ApiLogin} 之后，
 * 匿名请求都会在异常发生之前被拦成 {@code 200 {"code":60002}}。
 * 全仓已无可匿名触发 {@code BizException} 的 C 端端点——这是上一张卡收口的直接结果。
 *
 * <p>用 standalone MockMvc 直接驱动 advice：不起 Spring 上下文，
 * 因此测的是「异常 → 状态码 + 响应体」这一层的映射，与各服务的装配无关。
 */
class GlobalBizExceptionHandlerTest {

	private static final ObjectMapper MAPPER = new JsonMapper();

	private final GlobalBizExceptionHandler handler = new GlobalBizExceptionHandler();

	private MockMvc mockMvc(Object controller) {
		return MockMvcBuilders.standaloneSetup(controller)
				.setControllerAdvice(handler)
				.build();
	}

	// ------------------------------------------------------------------ 405

	@Test
	@DisplayName("R1 方法不支持 → 405，不再落 500 兜底")
	void methodNotSupported_returns405() throws Exception {
		// 只注册 DELETE /useraddress/{id}，用 GET 打过去 —— /useraddress/list 曾经就是这么变成 500 的
		mockMvc(new UserAddressLikeController())
				.perform(get("/useraddress/list"))
				.andExpect(status().isMethodNotAllowed())
				.andExpect(jsonPath("$.code").value(1))
				.andExpect(jsonPath("$.msg").value("请求方法不支持: GET"));
	}

	@Test
	@DisplayName("405 响应体不回 supportedMethods，免费给扫描器报菜名")
	void methodNotSupported_doesNotLeakSupportedMethods() {
		var e = new HttpRequestMethodNotSupportedException("GET", Set.of("DELETE", "POST"));
		R r = handler.handleMethodNotSupported(e);
		assertThat(String.valueOf(r.getMsg())).doesNotContain("DELETE").doesNotContain("POST");
	}

	// ------------------------------------------------------------------ 400

	@Test
	@DisplayName("R2 参数类型不匹配 → 400，且只回参数名不回类型")
	void typeMismatch_returns400_withoutType() throws Exception {
		mockMvc(new TypedController())
				.perform(get("/typed/abc"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.msg").value("参数格式不正确: id"))
				.andExpect(content().string(not(containsString("Long"))));
	}

	@Test
	@DisplayName("R2 请求体不可读 → 400，且响应体不含包路径/类名")
	void unreadableBody_returns400_withoutClassName() throws Exception {
		String body = mockMvc(new TypedController())
				.perform(post("/typed/body").contentType(MediaType.APPLICATION_JSON).content("{\"name\":"))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(body).doesNotContain("com.zhiflow")
				.doesNotContain("Payload")
				.doesNotContain("java.lang");
		assertThat(MAPPER.readTree(body).get("code").asInt()).isEqualTo(1);
	}

	@Test
	@DisplayName("R2 请求体字段类型错 → 400，只回 JSON 字段名（不回持有它的实体类名）")
	void unreadableBody_reportsJsonFieldNameOnly() throws Exception {
		String body = mockMvc(new TypedController())
				.perform(post("/typed/body").contentType(MediaType.APPLICATION_JSON)
						.content("{\"age\":\"not-a-number\"}"))
				.andExpect(status().isBadRequest())
				.andReturn().getResponse().getContentAsString();

		assertThat(MAPPER.readTree(body).get("msg").asString()).isEqualTo("请求体格式错误: age");
		assertThat(body).doesNotContain("Payload").doesNotContain("com.zhiflow");
	}

	@Test
	@DisplayName("R2 缺少上传文件 → 400，不再 500")
	void missingPart_returns400() throws Exception {
		mockMvc(new TypedController())
				.perform(multipart("/typed/upload"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.msg").value("缺少必需的文件参数: file"));
	}

	@Test
	@DisplayName("R2 方法级约束校验失败 → 400，只回参数名与校验文案")
	void constraintViolation_returns400() {
		R r = handler.handleConstraintViolation(new ConstraintViolationException("bad", Set.of()));
		assertThat(r.getCode()).isEqualTo(1);
		assertThat(r.getMsg()).isEqualTo("参数校验不通过");
	}

	@Test
	@DisplayName("既有行为不回归：缺必需 @RequestParam 仍是 400")
	void missingRequestParam_stillReturns400() throws Exception {
		mockMvc(new TypedController())
				.perform(get("/typed/need-param"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.msg").value("缺少必需参数: q"));
	}

	// ------------------------------------------------ R3 BizException 状态码归位

	@Nested
	@DisplayName("R3 BizException 状态码")
	class BizExceptionStatus {

		@Test
		@DisplayName("普通业务路径 → HTTP 200 + code=1，文案原样")
		void normalPath_returns200() {
			var request = new MockHttpServletRequest("GET", "/mallapi/reward-cycle/page");
			ResponseEntity<R> response =
					handler.handleGlobalException(new BizException("销售奖励配置未开启！"), request);

			assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
			assertThat(response.getBody()).isNotNull();
			assertThat(response.getBody().getCode()).isEqualTo(1);
			assertThat(response.getBody().getMsg()).isEqualTo("销售奖励配置未开启！");
		}

		/**
		 * R5 回调闸：渠道把 2xx 当 ACK 就会吃掉 15s~4h×10 次的重试自愈窗，
		 * 失败模式是「钱已收、单不入账」的静默资金损失。
		 * 已证实可达的一条：{@code notify-refunds} → {@code doOrderRefundsResult}
		 * → 秒杀已售数回补失败 {@code BizException("请重新提交")}。
		 */
		@Test
		@DisplayName("回调路径 → 仍是 HTTP 500（R5：不能让渠道把它当 ACK）")
		void callbackPath_keeps500() {
			String[] callbackUris = {
					"/mall/orderrefunds/notify-refunds/1",
					"/mallapi/orderinfo/notify-order-wx/1",
					"/mallapi/orderinfo/notify-order-wechat/1",
					"/mallapi/vipuserrecharge/notify-ali/1",
					"/mallapi/logistics/callback/1",
					"/mall/ordercashier/notify-global-wechat/1",
					"/mallapi/CASHIERORDER/NOTIFY/1",
			};
			for (String uri : callbackUris) {
				var request = new MockHttpServletRequest("POST", uri);
				ResponseEntity<R> response =
						handler.handleGlobalException(new BizException("请重新提交"), request);
				assertThat(response.getStatusCode())
						.as("回调路径必须保持非 2xx：%s", uri)
						.isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
			}
		}

		@Test
		@DisplayName("判据只看 URI，大小写不敏感，取不到 request 时按非回调处理")
		void callbackPredicate() {
			assertThat(GlobalBizExceptionHandler.isExternalCallbackRequest(null)).isFalse();
			assertThat(GlobalBizExceptionHandler.isExternalCallbackRequest(
					new MockHttpServletRequest("GET", "/mallapi/goodsspu/page"))).isFalse();
			assertThat(GlobalBizExceptionHandler.isExternalCallbackRequest(
					new MockHttpServletRequest("GET", "/mallapi/distributionuser/notify-pref"))).isTrue();
		}

		/**
		 * 直接调 handler 方法证明不了 Spring 能不能把 {@code HttpServletRequest} 注进
		 * {@code @ExceptionHandler} 的参数里——那是运行期才暴露的装配问题
		 * （「单测全绿 ≠ 起得来」的同一类坑）。这条走完整的 MockMvc 分发链。
		 */
		@Test
		@DisplayName("走完整分发链：普通路径 200、回调路径 500，HttpServletRequest 能被注入")
		void throughDispatcher() throws Exception {
			mockMvc(new BizThrowingController())
					.perform(get("/biz/normal"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.code").value(1))
					.andExpect(jsonPath("$.msg").value("销售奖励配置未开启！"));

			mockMvc(new BizThrowingController())
					.perform(post("/biz/notify-refunds/1"))
					.andExpect(status().isInternalServerError())
					.andExpect(jsonPath("$.code").value(1))
					.andExpect(jsonPath("$.msg").value("请重新提交"));
		}
	}

	/**
	 * 上传拒绝必须回非 200：三端 {@code uni.uploadFile} 封装都按 {@code statusCode === 200}
	 * 判成功，回 200 会变成 {@code resolve(undefined)}。
	 */
	@Test
	@DisplayName("BizBadRequestException → 400（子类分支优先于父类的 200）")
	void bizBadRequest_returns400() throws Exception {
		mockMvc(new TypedController())
				.perform(get("/typed/reject"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.code").value(1))
				.andExpect(jsonPath("$.msg").value("dir 非法：../../etc"));
	}

	@Test
	@DisplayName("BizBadRequestException 即使在回调路径上也仍是 400（它本就不是渠道 ACK 语义）")
	void bizBadRequest_notAffectedByCallbackCarveOut() {
		R r = handler.handleBizBadRequestException(new BizBadRequestException("file 不能为空"));
		assertThat(r.getCode()).isEqualTo(1);
		assertThat(r.getMsg()).isEqualTo("file 不能为空");
	}

	// ------------------------------------------------------------------ 兜底不动

	@Test
	@DisplayName("R4 Exception 兜底一行不动：仍是 500 + 8 位错误号，且不回传原文")
	void globalFallback_unchanged() {
		R r = handler.handleGlobalException(
				new IllegalStateException("com.zhiflow.mall.common.entity.CouponInfo.setListGoodsSpu"));
		assertThat(r.getCode()).isEqualTo(1);
		assertThat(r.getMsg()).matches("服务异常，请稍后重试（错误号 [0-9a-f]{8}）");
		assertThat(r.getMsg()).doesNotContain("com.zhiflow");
	}

	// ------------------------------------------------------------------ 测试用 controller

	@RestController
	@RequestMapping("/useraddress")
	static class UserAddressLikeController {
		@DeleteMapping("/{id}")
		public R removeById(@PathVariable("id") String id) {
			return R.ok();
		}
	}

	@RestController
	@RequestMapping("/biz")
	static class BizThrowingController {

		@GetMapping("/normal")
		public R normal() {
			throw new BizException("销售奖励配置未开启！");
		}

		@PostMapping("/notify-refunds/{tenantId}")
		public String notifyRefunds(@PathVariable("tenantId") String tenantId) {
			throw new BizException("请重新提交");
		}
	}

	@RestController
	@RequestMapping("/typed")
	static class TypedController {

		@GetMapping("/{id}")
		public R byId(@PathVariable("id") Long id) {
			return R.ok(id);
		}

		@PostMapping("/body")
		public R body(@RequestBody Payload payload) {
			return R.ok(payload);
		}

		@PostMapping("/upload")
		public R upload(@org.springframework.web.bind.annotation.RequestPart("file")
						org.springframework.web.multipart.MultipartFile file) {
			return R.ok();
		}

		@GetMapping("/need-param")
		public R needParam(@RequestParam("q") String q) {
			return R.ok(q);
		}

		@GetMapping("/reject")
		public R reject() {
			throw new BizBadRequestException("dir 非法：../../etc");
		}
	}

	static class Payload {
		private String name;
		private Integer age;

		public String getName() {
			return name;
		}

		public void setName(String name) {
			this.name = name;
		}

		public Integer getAge() {
			return age;
		}

		public void setAge(Integer age) {
			this.age = age;
		}
	}

}
