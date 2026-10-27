package com.zhiflow.arch;

import com.tngtech.archunit.core.domain.JavaMethod;

import java.util.List;
import java.util.Set;

/**
 * 门禁豁免清单。
 *
 * <p><b>豁免 ≠ baseline。</b>两者必须分清：
 * <ul>
 *   <li><b>豁免</b>：规则本身不该管这个位置，永久成立（如服务间 Feign 调用不该有 {@code @PreAuthorize}，
 *       挂上去会直接打断内部调用）。豁免项不会出现在 store 里。</li>
 *   <li><b>baseline</b>：存量技术债，将来要还。由 {@code FreezingArchRule} 冻结，只降不升。</li>
 * </ul>
 * 存量违例一律走 baseline，不要塞进豁免——塞进来就等于永久放弃。
 *
 * <p><b>禁止按类名模糊匹配豁免。</b>踩过的坑：{@code AiHealthController} 是「AI 健康」业务后台页、
 * {@code PromoNotifyTemplateController} 是「推广通知模板」后台页，若按 {@code Health}/{@code Notify}
 * 关键字豁免，会把两个普通业务写接口悄悄放行。因此下面全部用全限定名或受限包路径。
 */
final class ArchExemptions {

	/** 服务间 Feign 调用控制器：调用方是本系统服务而非用户，无 SecurityContext */
	private static final String FEIGN_PACKAGE_MARKER = ".controller.feign.";

	/**
	 * {@code @Inside} 标记的服务间调用端点。比包路径判定更准确——服务间端点并非都放在
	 * feign 包下（例如 upms 的 {@code SysLogController.save} 与
	 * {@code SysErrorLogController.save} 就在普通 controller 包里）。
	 *
	 * <p>这类端点加 {@code @PreAuthorize} 会直接打断内部调用；审计则由调用方侧记录。
	 */
	private static final String ANN_INSIDE = "com.zhiflow.common.security.annotation.Inside";

	/**
	 * 探活/健康检查端点：由 k8s、1Panel、负载均衡高频轮询。
	 * 既不需要权限，也不该写审计（会以每分钟数十条淹没 sys_log）。
	 */
	private static final Set<String> HEALTH_PROBE_CLASSES = Set.of();

	/**
	 * 外部平台回调入口：调用方是微信 / 支付渠道，靠各自签名验签而非本系统权限体系。
	 * 注意只列真正的回调类；支付类里回调与业务方法混杂，故支付走下面的方法级判定。
	 */
	private static final Set<String> EXTERNAL_CALLBACK_CLASSES = Set.of();

	/**
	 * 外部渠道回调方法：支付渠道、物流平台等第三方回调本系统的入口。调用方是外部平台，
	 * 靠各自的签名验签而非本系统鉴权体系，挂 {@code @PreAuthorize}/{@code @ApiLogin} 都不成立。
	 *
	 * <p><b>按方法名而非类名判定</b>：{@code CashierPayController}、{@code OrderInfoController}
	 * 这些类内同时有「用户下单」和「渠道回调」两类方法，类级豁免会把下单接口一起放行。
	 * 反过来若按类名关键字匹配，又会误伤 {@code PromoNotifyTemplateController}（推广通知模板
	 * 后台页，方法名是 save/update，不含 notify）。
	 *
	 * <p>实测命中的都是真回调，如 {@code notifyOrder}。
	 */
	/**
	 * 认证服务器上「自己解析 Bearer、只操作调用者本人会话」的端点。
	 *
	 * <p>为什么挂不了 {@code @PreAuthorize}：{@code WebSecurityConfig} 把 {@code /token/**}
	 * 整体设成 {@code permitAll}（OAuth2 的取 token/登录页/check_token 本来就必须匿名可达）。
	 * 这些路径下没有 SecurityContext，{@code isAuthenticated()} 恒假——挂上去等于把功能打死。
	 * 它们的鉴权是方法体内**自行解析 Authorization 头**并只对该 token 归属的授权做操作。
	 *
	 * <p>为什么走豁免而不是留 baseline：baseline 的语义是「存量技术债、将来要还」，
	 * 而这里是结构性的——除非改动 OAuth2 授权服务器的过滤链（不可能），否则永远不会变。
	 * {@code logout} 原本挂在 baseline 里，2026-08-02 一并迁到这里，baseline 相应 -1。
	 *
	 * <p>范围刻意写死方法全名，不用类级/前缀匹配：{@code TokenController} 里还有
	 * {@code @Inside} 的服务间端点和匿名端点，类级豁免会把它们一起放行。
	 */
	private static final Set<String> AUTH_SERVER_SELF_SERVICE_METHODS = Set.of(
			"com.zhiflow.auth.controller.TokenController.logout",
			"com.zhiflow.auth.controller.TokenController.revokeCurrentSession");

	/**
	 * 后台账号在<b>拿到令牌之前</b>必须能调的写端点：首次登录 / 管理员重置口令之后，登录被拒（凭证过期），
	 * 只能凭原口令在这里改密。挂 {@code @PreAuthorize} 等于把「改密才能登录」锁在登录之后，是结构性的。
	 * 防爆破在方法上：{@code @RateLimit} + 与密码登录共用的失败锁定。刻意写死方法全名。
	 */
	private static final Set<String> ADMIN_PRE_LOGIN_METHODS = Set.of(
			"com.zhiflow.upms.admin.controller.SysUserController.changeExpiredPassword");

	private static final List<String> EXTERNAL_CALLBACK_METHOD_PREFIXES = List.of("notify");

	private static final List<String> EXTERNAL_CALLBACK_METHOD_MARKERS = List.of("callback");

	/**
	 * mall-api 上<b>必须匿名可达</b>的写端点：登录 / 注册 / 登出 / 换 openid / 验证码校验 / JSSDK 签名。
	 *
	 * <p>给它们挂 {@code @ApiLogin} 等于把「登录」这件事锁在登录之后，是结构性的，不是待还的债——
	 * 所以走豁免而不是 baseline（两者的区别见本类顶部）。
	 *
	 * <p><b>刻意写死方法全名，不用类级或前缀匹配</b>：{@code UserInfoController} 同一个类里还有
	 * 15 处带 {@code @ApiLogin} 的正常业务方法，类级豁免会把它们一起放行；
	 * 而按 {@code login} 前缀匹配会误伤将来任何叫 {@code loginXxx} 的业务方法。
	 *
	 * <p>{@code logout} 也在列：它自己从 {@code third-session} 头解析会话并只清该会话，
	 * 与 {@link #AUTH_SERVER_SELF_SERVICE_METHODS} 里 {@code TokenController.logout} 同款理由。
	 */
	private static final Set<String> MALL_API_ANONYMOUS_ENTRY_METHODS = Set.of(
			"com.zhiflow.mall.api.controller.UserInfoController.login",
			"com.zhiflow.mall.api.controller.UserInfoController.loginByPhone",
			"com.zhiflow.mall.api.controller.UserInfoController.loginByPhoneMa",
			"com.zhiflow.mall.api.controller.UserInfoController.logout",
			"com.zhiflow.mall.api.controller.UserInfoController.register",
			"com.zhiflow.mall.api.controller.WxUserController.code2OpenIdMa",
			"com.zhiflow.mall.api.controller.WxUserController.code2OpenIdMp",
			"com.zhiflow.mall.api.controller.WxUserController.loginMa",
			"com.zhiflow.mall.api.controller.WxUserController.loginMp",
			"com.zhiflow.mall.api.controller.WxUserController.isGzh",
			// generate 用的是不限 method 的 @RequestMapping，被 WRITE_ENDPOINT「从严处理」判成写端点。
			// 它是登录 / 找回密码 / 申请代理三条流程的第一步，必须先于登录可达。
			"com.zhiflow.mall.api.controller.CaptchaController.generate",
			"com.zhiflow.mall.api.controller.CaptchaController.check",
			"com.zhiflow.mall.api.controller.CaptchaController.check2");

	/**
	 * R1d 登记表：<b>确属必须用 GET 表达、且方法体里直接写库</b>的端点。
	 *
	 * <p>这不是豁免而是<b>登记</b>——每一条都要有人念出名字和理由。用登记而不是
	 * {@code FreezingArchRule} baseline，是因为这一类只有个位数，而 baseline 会把它们
	 * 吸进一个几百行的大文件里，从此没人再逐条看。「用 GET 表达写语义」能活这么久
	 * 正是因为没有任何机制逼人正视它。
	 *
	 * <p><b>登记 ≠ 无风险。</b>两条都是匿名可达的写：
	 * <ul>
	 *   <li>{@code GlobalHotWordController.save}（{@code GET /globalhotword?word=}）——
	 *       搜索埋点。必须匿名：未登录用户也能搜索，要求登录等于把热搜榜变成登录用户专属。
	 *       写入内容是<b>调用方可控的 ≤50 字文本</b>，且经 {@code /getTop} 展示给全体用户，
	 *       服务端只做「trim 后长度 ≥2」与截断 50 字。<b>无限流 ⇒ 可被刷榜</b>，
	 *       缓解手段是边缘层对匿名请求限流。</li>
	 *   <li>{@code GoodsAppraisesController.getById}（{@code GET /goodsappraises/{id}}）——
	 *       评价详情浏览量 {@code view_num + 1}。同样匿名可刷，但写入内容不可控（只是计数），
	 *       影响面限于该条评价的展示排序。</li>
	 * </ul>
	 *
	 * <p>另外三条是<b>「查不到就补建空记录」</b>的惰性初始化，风险层级完全不同——
	 * 三者都有方法级 {@code @ApiLogin}，写入内容是一条只带 {@code userId} 的空记录、
	 * 无任何调用方可控字段，且用户身份不可伪造：
	 * <ul>
	 *   <li>{@code UserBalanceController.getById} / {@code UserPointsController.getById}：
	 *       收 {@code {userId}} 路径参，但方法首段即
	 *       {@code if (!StrUtil.equals(ThirdSessionHolder.getUserId(), userId)) return R.failed(...)}；</li>
	 *   <li>{@code UserInfoController.getById}：<b>连参数都没有</b>，
	 *       id 直接取自 {@code ThirdSessionHolder.getUserId()}。</li>
	 * </ul>
	 * 登记而不是改成 POST：它们语义上确实是「查我的余额/积分/资料」，改动词会打断四端调用方。
	 *
	 * <p>其余「读接口顺带写」的形态（商详记足迹 / 热度分 / Redis 缓存 / 验证码）
	 * <b>不在此表</b>：它们的写动作在 service 层而不在 controller 里，R1d 只判直接调用，
	 * 判据见 {@code ArchPredicates.READ_ENDPOINT_WITH_PERSISTENCE_WRITE} 的注释。
	 * 其中商详那条另有一层保护——{@code recordUserActivity} 被
	 * {@code if (StrUtil.isNotBlank(userId))} 包着，匿名根本走不到。
	 */
	// 2026-10-20 开源分支：五条登记全部失效 —— 热词 GET 写、评价 / 余额 / 积分三个控制器随功能删除，
	// UserInfoController.getById 重写后只读不写。登记表清空，以后出现的 GET 写一律先改动词。
	private static final Set<String> READ_SIDE_WRITE_METHODS = Set.of();

	private ArchExemptions() {
	}

	/** R1d：该 GET 端点是否已在 {@link #READ_SIDE_WRITE_METHODS} 里登记 */
	static boolean isRegisteredReadSideWrite(JavaMethod method) {
		return READ_SIDE_WRITE_METHODS.contains(method.getOwner().getFullName() + "." + method.getName());
	}

	/**
	 * 豁免 {@code @PreAuthorize} 要求：无用户上下文的入口。
	 */
	static boolean exemptFromPreAuthorize(JavaMethod method) {
		String owner = method.getOwner().getFullName();
		if (owner.contains(FEIGN_PACKAGE_MARKER) || isInsideEndpoint(method)) {
			return true;
		}
		if (HEALTH_PROBE_CLASSES.contains(owner) || EXTERNAL_CALLBACK_CLASSES.contains(owner)) {
			return true;
		}
		if (AUTH_SERVER_SELF_SERVICE_METHODS.contains(owner + "." + method.getName())
				|| ADMIN_PRE_LOGIN_METHODS.contains(owner + "." + method.getName())) {
			return true;
		}
		return isExternalCallback(method);
	}

	/**
	 * 豁免 R1c（mall-api 写端点必须有<b>方法级</b> {@code @ApiLogin}）。
	 *
	 * <p>比 {@link #exemptFromPreAuthorize} 窄：那条管全仓两套鉴权体系，这条只管 C 端，
	 * 且只认方法级注解。三类结构性豁免：
	 * <ul>
	 *   <li>{@code @Inside(value=true)} 的服务间端点——外部调不通，挂 {@code @ApiLogin} 会打断内部调用；</li>
	 *   <li>外部渠道回调（{@code notify*} / {@code *callback*}）——调用方是微信/支付渠道，靠各自验签；</li>
	 *   <li>{@link #MALL_API_ANONYMOUS_ENTRY_METHODS} 里逐个登记的登录/注册/取码入口。</li>
	 * </ul>
	 *
	 * <p><b>没有「读语义误用 POST」这一类豁免</b>：那些（GaodeMap / SysDict.getListPage /
	 * GoodsTrendRule.getTrendRuleDetail 等）是存量，改成 GET 或补注解都能还，
	 * 归 baseline 而不是永久放弃。
	 */
	static boolean exemptFromMethodLevelApiLogin(JavaMethod method) {
		String owner = method.getOwner().getFullName();
		if (owner.contains(FEIGN_PACKAGE_MARKER) || isInsideEndpoint(method)) {
			return true;
		}
		if (isExternalCallback(method)) {
			return true;
		}
		return MALL_API_ANONYMOUS_ENTRY_METHODS.contains(owner + "." + method.getName());
	}

	/**
	 * 豁免 {@code @SysLog} 要求。
	 *
	 * <p>比权限豁免严格得多：<b>外部回调必须留痕</b>（支付回调尤其——「已收款未入账」类问题全靠它复盘），
	 * 所以回调类不在此豁免。只放行探活轮询和服务间调用（后者由调用方侧记录）。
	 */
	/**
	 * C 端服务包前缀。<b>C 端不做操作审计</b>，理由是技术性的而非偷懒：
	 *
	 * <p>{@code sys_log.createBy} / {@code createId} 取自 {@code SecurityContextHolder}
	 * （后台 OAuth2 身份），而 C 端身份存在 {@code ThirdSessionHolder} 这个独立 ThreadLocal 里，
	 * <b>两者不通</b>。给 C 端接口挂 {@code @SysLog}，落库记录的「操作人」是空的——
	 * 审计日志的核心是「谁在什么时候做了什么」，连「谁」都记不到就失去了意义，
	 * 同时还要承担 C 端流量级的写入放大。
	 *
	 * <p>C 端的可观测性由另外两条链路承担：错误留痕走 {@code ErrorLogRecorder}
	 * （见 S12），用户行为分析走既有的三条访问采集链（AccessLog PRO / clklog / nginx）。
	 *
	 * <p>若将来要给 C 端做审计，前提是先把 {@code ThirdSessionHolder} 的身份接进
	 * {@code SysLogUtils}，而不是直接挂注解。
	 */
	private static final String C_END_PACKAGE_MARKER = "com.zhiflow.mall.api.";

	/**
	 * 用 POST 只是为了承载一大串 id 的<b>只读查询</b>，不是写操作，不做操作审计。
	 *
	 * <p>2026-09-15 后台客服工作台轮询这两个接口，一天在 sys_log 里写了 5.9 万条「查询」审计
	 * （批量查询用户简要信息 32771、list查询通过ids 26046），平时整张表一天几十到一千条 ——
	 * 真正的写操作留痕被淹没。进这个名单必须是方法体只读、没有任何副作用。
	 */
	private static final Set<String> READ_ONLY_POST_METHODS = Set.of(
			"com.zhiflow.mall.admin.controller.UserInfoController.batchGet",
			"com.zhiflow.mall.admin.controller.GoodsSpuController.getGoodsSpuListByIds");

	static boolean exemptFromSysLog(JavaMethod method) {
		String owner = method.getOwner().getFullName();
		return owner.contains(FEIGN_PACKAGE_MARKER) || HEALTH_PROBE_CLASSES.contains(owner)
				|| isInsideEndpoint(method)
				|| owner.startsWith(C_END_PACKAGE_MARKER)
				|| READ_ONLY_POST_METHODS.contains(owner + "." + method.getName());
	}

	/**
	 * 方法或其所在类带 {@code @Inside} <b>且 {@code value()} 为 true</b>。
	 *
	 * <p><b>必须判 value，只看注解在不在是错的。</b>{@code @Inside} 的 {@code value} 是语义开关：
	 * <ul>
	 *   <li>{@code value=true}（默认）——服务间内部端点。{@code BaseSecurityInsideAspect} 校验
	 *       {@code FROM_IN} 头，外部调不通。这类才该豁免鉴权要求（挂 {@code @PreAuthorize} 会打断内部调用）。</li>
	 *   <li>{@code value=false}——<b>对外完全开放</b>。{@code BaseSecurityInsideAspect:41} 的
	 *       {@code if (inside.value() && ...)} 直接跳过校验，且 {@code PermitAllUrlProperties} 会把该路径
	 *       并入 {@code releaseUrls} → {@code permitAll}，<b>连 Bearer token 都不要</b>。
	 *       这是最暴露的一类端点，豁免它等于门禁对它完全失明。</li>
	 * </ul>
	 *
	 * <p>项目别处早已按 value 判定（{@code ThirdSessionInterceptor:63} 注释原文：
	 * 「⚠️ 必须判 value：{@code @Inside(value=false)} 是「对外开放」标记」），本处此前遗漏，
	 * 于 2026-07-25 修正——修正前 {@code PhoneController.sendSmsCode}（短信取码）
	 * 与 {@code SysUserController} 忘记密码这两个无鉴权后台端点被静默放行。
	 *
	 * <p>方法级注解优先于类级：类上 {@code @Inside} 而方法上 {@code @Inside(false)} 时，以方法为准。
	 */
	private static boolean isInsideEndpoint(JavaMethod method) {
		if (method.isAnnotatedWith(ANN_INSIDE)) {
			return insideValueOf(method.getAnnotationOfType(ANN_INSIDE));
		}
		if (method.getOwner().isAnnotatedWith(ANN_INSIDE)) {
			return insideValueOf(method.getOwner().getAnnotationOfType(ANN_INSIDE));
		}
		return false;
	}

	/** {@code @Inside} 的 value，缺省为 true（与注解定义一致） */
	private static boolean insideValueOf(com.tngtech.archunit.core.domain.JavaAnnotation<?> annotation) {
		return annotation.get("value").map(v -> (Boolean) v).orElse(Boolean.TRUE);
	}

	private static boolean isExternalCallback(JavaMethod method) {
		String name = method.getName().toLowerCase();
		return EXTERNAL_CALLBACK_METHOD_PREFIXES.stream().anyMatch(name::startsWith)
				|| EXTERNAL_CALLBACK_METHOD_MARKERS.stream().anyMatch(name::contains);
	}
}
