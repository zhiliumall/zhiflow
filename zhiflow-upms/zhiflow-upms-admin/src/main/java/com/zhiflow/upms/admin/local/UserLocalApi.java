package com.zhiflow.upms.admin.local;

import com.zhiflow.api.upms.UserApi;
import com.zhiflow.common.core.util.R;
import com.zhiflow.common.security.internal.InternalCallContext;
import com.zhiflow.upms.admin.controller.SysUserController;
import com.zhiflow.upms.admin.controller.SysUserSecurityController;
import com.zhiflow.upms.common.dto.TotpLoginRequest;
import com.zhiflow.upms.admin.service.SysOauthClientService;
import com.zhiflow.upms.common.entity.SysOauthClient;
import com.zhiflow.upms.common.dto.AuthUserInfo;
import com.zhiflow.upms.common.dto.SysUserInfo;
import com.zhiflow.upms.common.dto.SysUserQuery;
import org.springframework.context.annotation.Lazy;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * {@link UserApi} 的进程内实现（7 个方法）。
 *
 * <p>当初整接口被生成器跳过的原因：方法散在 <b>3 个 controller</b>
 * （SysUserController ×5、SysUserSecurityController ×1；OAuth 客户端直接读 SysOauthClientService），
 * 而生成器只在单 controller 里找 —— 「对不上」是工具视野问题，不是端点缺失。
 * 唯一真缺失的 {@code listClientDetails}（GET /client/list 从来不存在，
 * 正是契约回归钉住的 _known_gap）已从契约删除。
 */
@Primary
@Component
public class UserLocalApi implements UserApi {

	private final SysUserController sysUserController;

	private final SysUserSecurityController sysUserSecurityController;

	private final SysOauthClientService sysOauthClientService;

	/** ⚠️ 显式构造器 + {@code @Lazy}（本仓没配 Lombok copyableAnnotations，理由同其余 47 个实现）。 */
	public UserLocalApi(@Lazy SysUserController sysUserController,
			@Lazy SysUserSecurityController sysUserSecurityController,
			@Lazy SysOauthClientService sysOauthClientService) {
		this.sysUserController = sysUserController;
		this.sysUserSecurityController = sysUserSecurityController;
		this.sysOauthClientService = sysOauthClientService;
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<AuthUserInfo> info(String username, String from) {
		return InternalCallContext.call(() -> this.sysUserController.info(username));
	}

	@Override
	@SuppressWarnings("unchecked")
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<List<SysUserInfo>> getInsideUserList(SysUserQuery sysUser, String from) {
		return InternalCallContext.call(() -> this.sysUserController.getInsideUserList(sysUser));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<Boolean> checkTotpForLogin(TotpLoginRequest request, String from) {
		return InternalCallContext.call(() -> this.sysUserSecurityController.checkTotpForLogin(request));
	}

	@Override
	@Transactional(propagation = Propagation.REQUIRES_NEW)
	public R<SysOauthClient> getClientDetailsById(String clientId, String from) {
		// 开源版没有终端管理页（S-52），OAuth 客户端写在基线种子里，这里直接读库
		return R.ok(this.sysOauthClientService.getById(clientId));
	}
}
