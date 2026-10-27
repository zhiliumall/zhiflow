package com.zhiflow.upms.admin.projection;

import com.zhiflow.upms.common.dto.AgreementInfo;
import com.zhiflow.upms.common.dto.AreaInfo;
import com.zhiflow.upms.common.dto.AreaQuery;
import com.zhiflow.upms.common.dto.AuthUserIdentity;
import com.zhiflow.upms.common.dto.AuthUserInfo;
import com.zhiflow.upms.common.dto.RoleDataScope;
import com.zhiflow.upms.common.dto.SysDictInfo;
import com.zhiflow.upms.common.dto.SysDictQuery;
import com.zhiflow.upms.common.dto.SysDictValueInfo;
import com.zhiflow.upms.common.dto.SysLogLoginPayload;
import com.zhiflow.upms.common.dto.SysLogPayload;
import com.zhiflow.upms.common.dto.SysUserInfo;
import com.zhiflow.upms.common.dto.SysUserQuery;
import com.zhiflow.upms.common.dto.TenantInfo;
import com.zhiflow.upms.common.dto.UserInfo;
import com.zhiflow.upms.common.entity.AgreementConfig;
import com.zhiflow.upms.common.entity.SysDict;
import com.zhiflow.upms.common.entity.SysDictValue;
import com.zhiflow.upms.common.entity.SysLog;
import com.zhiflow.upms.common.entity.SysLogLogin;
import com.zhiflow.upms.common.entity.SysRole;
import com.zhiflow.upms.common.entity.SysTenant;
import com.zhiflow.upms.common.entity.SysUser;
import com.zhiflow.upms.common.entity.TableArea;

import java.util.List;

/**
 * upms 的<b>服务间契约</b>与持久化实体之间的投影，一处定义、各 {@code /inside} 端点共用。
 *
 * <h2>为什么要有这一层</h2>
 * <p>B5 之前，upms 的 16 个契约里有 12 个直接把 MyBatis 实体（{@code SysTenant} /
 * {@code SysUser} / {@code SysLog} …）当返回值或请求体。那意味着<b>服务端改一个字段名，
 * 契约不编译报错、不抛异常，只在运行时静默丢字段</b>——本轮全量迁 gRPC 要修的正是这个病，
 * 而 proto 必须有一份「字段是什么」的确定答案，实体给不了。
 *
 * <p>映射写在这里而不是各 controller 里：{@code /inside} 端点与 gRPC 服务端<b>共用同一个
 * controller 方法</b>，两条传输必须投影出逐字段相同的东西；抄两份必然漂。
 *
 * <p><b>没有任何业务判断</b>——只有取值与赋值。哪些字段该跨边界，是每个 DTO 自己的 javadoc
 * 在交代（带推导与调用点计数）。
 */
public final class UpmsInsideProjections {

	private UpmsInsideProjections() {
	}

	// ===================================================================== 租户

	public static TenantInfo tenant(SysTenant src) {
		if (src == null) {
			return null;
		}
		TenantInfo out = new TenantInfo();
		out.setId(src.getId());
		out.setName(src.getName());
		out.setBrandName(src.getBrandName());
		out.setStatus(src.getStatus());
		out.setExpireTime(src.getExpireTime());
		return out;
	}

	public static List<TenantInfo> tenants(List<SysTenant> src) {
		return src == null ? null : src.stream().map(UpmsInsideProjections::tenant).toList();
	}

	// ===================================================================== 协议 / 门户域名

	public static AgreementInfo agreement(AgreementConfig src) {
		if (src == null) {
			return null;
		}
		AgreementInfo out = new AgreementInfo();
		out.setId(src.getId());
		out.setAgreementType(src.getAgreementType());
		out.setAgreementName(src.getAgreementName());
		out.setAgreementContent(src.getAgreementContent());
		// 截到秒：REST 的 JSON 只到秒（JavaTimeModule 的 yyyy-MM-dd HH:mm:ss），gRPC 的 DateTime 与单体本地直调却会带出 PG 的微秒。
		// mall-api 的推广合同签约版本号 = updateTime 的 epoch 毫秒，精度随传输变就是版本号随传输变（r3 followup C2）。
		// 三条路径共用这份投影，在这里统一；存量签约记录的版本号都是 REST 时代的整秒，所以截秒而不是让 REST 也带纳秒。
		out.setCreateTime(truncateToSeconds(src.getCreateTime()));
		out.setUpdateTime(truncateToSeconds(src.getUpdateTime()));
		return out;
	}

	private static java.time.LocalDateTime truncateToSeconds(java.time.LocalDateTime value) {
		return value == null ? null : value.truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
	}

	// ===================================================================== 地区

	/** 请求方向：具名查询条件 → MyBatis-Plus 的条件构造实体（非 null 字段拼等值 WHERE）。 */
	public static TableArea areaQuery(AreaQuery src) {
		TableArea out = new TableArea();
		if (src == null) {
			return out;
		}
		out.setId(src.getId());
		out.setPid(src.getPid());
		out.setShortname(src.getShortname());
		out.setName(src.getName());
		out.setMergename(src.getMergename());
		out.setLevel(src.getLevel());
		out.setPinyin(src.getPinyin());
		out.setCode(src.getCode());
		out.setZip(src.getZip());
		out.setFirst(src.getFirst());
		out.setLng(src.getLng());
		out.setLat(src.getLat());
		return out;
	}

	public static AreaInfo area(TableArea src) {
		if (src == null) {
			return null;
		}
		AreaInfo out = new AreaInfo();
		out.setId(src.getId());
		out.setPid(src.getPid());
		out.setShortname(src.getShortname());
		out.setName(src.getName());
		out.setMergename(src.getMergename());
		out.setLevel(src.getLevel());
		out.setPinyin(src.getPinyin());
		out.setCode(src.getCode());
		out.setZip(src.getZip());
		out.setFirst(src.getFirst());
		out.setLng(src.getLng());
		out.setLat(src.getLat());
		return out;
	}

	public static List<AreaInfo> areas(List<TableArea> src) {
		return src == null ? null : src.stream().map(UpmsInsideProjections::area).toList();
	}

	// ===================================================================== 角色数据权限

	public static RoleDataScope roleDataScope(SysRole src) {
		if (src == null) {
			return null;
		}
		RoleDataScope out = new RoleDataScope();
		out.setDsType(src.getDsType());
		out.setDsScope(src.getDsScope());
		return out;
	}

	// ===================================================================== 字典

	public static SysDict dictQuery(SysDictQuery src) {
		SysDict out = new SysDict();
		if (src == null) {
			return out;
		}
		out.setId(src.getId());
		out.setType(src.getType());
		out.setDescription(src.getDescription());
		out.setRemarks(src.getRemarks());
		return out;
	}

	public static SysDictInfo dict(SysDict src) {
		if (src == null) {
			return null;
		}
		SysDictInfo out = new SysDictInfo();
		out.setId(src.getId());
		out.setType(src.getType());
		out.setDescription(src.getDescription());
		out.setRemarks(src.getRemarks());
		return out;
	}

	public static List<SysDictInfo> dicts(List<SysDict> src) {
		return src == null ? null : src.stream().map(UpmsInsideProjections::dict).toList();
	}

	public static SysDictValueInfo dictValue(SysDictValue src) {
		if (src == null) {
			return null;
		}
		SysDictValueInfo out = new SysDictValueInfo();
		out.setId(src.getId());
		out.setDictId(src.getDictId());
		out.setValue(src.getValue());
		out.setLabel(src.getLabel());
		out.setType(src.getType());
		out.setDescription(src.getDescription());
		out.setSort(src.getSort());
		out.setRemarks(src.getRemarks());
		return out;
	}

	public static List<SysDictValueInfo> dictValues(List<SysDictValue> src) {
		return src == null ? null : src.stream().map(UpmsInsideProjections::dictValue).toList();
	}

	// ===================================================================== 用户

	/**
	 * 鉴权链路的投影。「一个都不能少」的字段清单见 {@link AuthUserIdentity} 的 javadoc ——
	 * 少一个就是「用户登录不上」，而且可能只在四种登录方式里的某一种上复现。
	 */
	public static AuthUserInfo authUser(UserInfo src) {
		if (src == null) {
			return null;
		}
		AuthUserInfo out = new AuthUserInfo();
		out.setRoles(src.getRoles());
		out.setPermissions(src.getPermissions());
		SysUser user = src.getSysUser();
		if (user != null) {
			AuthUserIdentity identity = new AuthUserIdentity();
			identity.setId(user.getId());
			identity.setOrganId(user.getOrganId());
			identity.setType(user.getType());
			identity.setTenantId(user.getTenantId());
			identity.setUsername(user.getUsername());
			identity.setPassword(user.getPassword());
			identity.setTenantIds(user.getTenantIds());
			// 注意：不是数据库原始列，是 SysUserController#setUserLockFlag 结合
			// 租户锁定/欠费与店铺状态算出来的值。
			identity.setLockFlag(user.getLockFlag());
			identity.setPasswordChangeRequired(user.getPasswordChangeRequired());
			out.setUser(identity);
		}
		return out;
	}

	public static SysUser userQuery(SysUserQuery src) {
		SysUser out = new SysUser();
		if (src == null) {
			return out;
		}
		out.setId(src.getId());
		out.setType(src.getType());
		return out;
	}

	public static SysUserInfo user(SysUser src) {
		if (src == null) {
			return null;
		}
		SysUserInfo out = new SysUserInfo();
		out.setId(src.getId());
		out.setUsername(src.getUsername());
		out.setEmail(src.getEmail());
		out.setPhone(src.getPhone());
		out.setAvatar(src.getAvatar());
		out.setOrganId(src.getOrganId());
		out.setTenantId(src.getTenantId());
		out.setType(src.getType());
		out.setNickName(src.getNickName());
		out.setLockFlag(src.getLockFlag());
		out.setDelFlag(src.getDelFlag());
		out.setCreateTime(src.getCreateTime());
		out.setUpdateTime(src.getUpdateTime());
		return out;
	}

	public static List<SysUserInfo> users(List<SysUser> src) {
		return src == null ? null : src.stream().map(UpmsInsideProjections::user).toList();
	}

	// ===================================================================== 日志（写入方向）

	public static SysLog sysLog(SysLogPayload src) {
		SysLog out = new SysLog();
		if (src == null) {
			return out;
		}
		out.setType(src.getType());
		out.setTitle(src.getTitle());
		out.setContent(src.getContent());
		out.setCreateId(src.getCreateId());
		out.setCreateBy(src.getCreateBy());
		out.setRemoteAddr(src.getRemoteAddr());
		out.setUserAgent(src.getUserAgent());
		out.setRequestUri(src.getRequestUri());
		out.setMethod(src.getMethod());
		out.setParams(src.getParams());
		out.setTime(src.getTime());
		out.setException(src.getException());
		out.setServiceId(src.getServiceId());
		return out;
	}

	public static SysLogLogin sysLogLogin(SysLogLoginPayload src) {
		SysLogLogin out = new SysLogLogin();
		if (src == null) {
			return out;
		}
		out.setType(src.getType());
		out.setGrantType(src.getGrantType());
		out.setCreateId(src.getCreateId());
		out.setCreateBy(src.getCreateBy());
		out.setRemoteAddr(src.getRemoteAddr());
		out.setUserAgent(src.getUserAgent());
		out.setRequestUri(src.getRequestUri());
		out.setParams(src.getParams());
		out.setAddress(src.getAddress());
		out.setException(src.getException());
		// 登录发生在租户上下文建立之前，这个字段必须由调用方显式带过来。
		out.setTenantId(src.getTenantId());
		return out;
	}
}
