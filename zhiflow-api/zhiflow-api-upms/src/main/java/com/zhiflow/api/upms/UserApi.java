package com.zhiflow.api.upms;

import com.zhiflow.upms.common.entity.SysOauthClient;
import com.zhiflow.upms.common.dto.AuthUserInfo;
import com.zhiflow.upms.common.dto.SysUserInfo;
import com.zhiflow.upms.common.dto.SysUserQuery;
import com.zhiflow.upms.common.dto.TotpLoginRequest;
import com.zhiflow.common.core.constant.SecurityConstants;
import com.zhiflow.common.core.util.R;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.service.annotation.*;

import java.util.List;

/**
 * 用户服务 API 接口定义
 *
 * 说明：此接口定义了 UPMS 服务对外提供的用户管理相关 API
 * 实现：zhiflow-upms-admin 模块的 SysUserController
 * 调用：其他微服务通过 HttpServiceProxyFactory 创建此接口的代理进行调用
 *
 */
@HttpExchange(url = "/upms", accept = "application/json", contentType = "application/json")
public interface UserApi {

	/**
	 * 通过用户名查询用户、角色信息
	 *
	 * @param username 用户名
	 * @param from     调用标志
	 * @return R
	 */
	@GetExchange("/user/info/{username}")
	R<AuthUserInfo> info(@PathVariable("username") String username,
					@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 分页查询用户
	 *
	 * @param sysUser 用户信息
	 * @param from    调用标志
	 * @return R
	 */
	@PostExchange("/user/inside/list")
	R<List<SysUserInfo>> getInsideUserList(@RequestBody SysUserQuery sysUser,
									  @RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * Checks a TOTP code for an already password-authenticated account. A false
	 * result covers both missing/invalid codes; no secret leaves UPMS.
	 */
	@PostExchange("/user/security/totp/login-check")
	R<Boolean> checkTotpForLogin(@RequestBody TotpLoginRequest request,
								@RequestHeader(SecurityConstants.FROM) String from);

	/**
	 * 通过clientId 查询客户端信息
	 *
	 * @param clientId 客户端ID
	 * @param from     调用标志
	 * @return R
	 */
	@GetExchange("/client/getClientDetailsById/{clientId}")
	R<SysOauthClient> getClientDetailsById(@PathVariable("clientId") String clientId,
										  @RequestHeader(SecurityConstants.FROM) String from);

	// [B5] ancestorUsers 已删除 —— 死边（2026-09-14 定案）：
	// `grep -rn "ancestorUsers"` 全仓（含 src/test）只剩契约声明、UserLocalApi 的实现与
	// upms 自己的 controller 三处，**零业务调用方**。对照样本：同一条 grep 口径对
	// getInsideUserList 命中 4 个真实调用点，所以不是搜漏了。
	// 它返回 R<List<SysUser>>（ENTITY 债），迁 gRPC 要为它定一份没人消费的投影 DTO ——
	// 那是给死代码建契约。controller 侧 `GET /user/ancestor/{username}` 一并删除
	// （@Inside 端点，只可能被契约打到，契约没了就无人可达）。

	// listClientDetails 已删除 —— 幽灵方法（2026-08-16 定案）：
	// upms 的 SysOauthClientController 只有 GET /client/page，从来没有过 GET /client/list。
	// 它正是契约回归里长期钉住的那条 _known_gap（UserApi.listClientDetails → 424）——
	// 424 的真因不是「缺 token」，是端点不存在。业务代码零调用方（grep 复核过）。
}
