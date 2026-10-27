package com.zhiflow.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * baseline 完整性守卫。
 *
 * <p><b>存在理由</b>：{@code FreezingArchRule} 只能拦「新增违例」，拦不住 baseline 本身被洗白。
 * 实测踩到的失效路径——把规则的 {@code because(...)} 文案改一个字，ArchUnit 就认为这是一条
 * <b>新规则</b>，为它建一条空 baseline 并把全部存量违例重新冻结，旧条目变成孤儿。整个过程
 * 静默通过，{@code allowStoreCreation=false} 也管不到（那个开关只管 store 目录是否能被创建）。
 *
 * <p>本测试把每条规则的违例条数钉在代码里，于是：
 * <ul>
 *   <li>baseline 被重置/清空 → 条数不符，失败</li>
 *   <li>规则改名产生孤儿条目 → 条目总数不符，失败</li>
 *   <li>存量违例被修掉 → 条数下降，失败并提示更新数字——强制把进展记录下来，而不是悄悄漂移</li>
 * </ul>
 */
@Order(2) // 必须后于 ArchGateTest：校验的是它落盘后的 store 状态（见 junit-platform.properties）
class BaselineIntegrityTest {

	private static final Path STORE = Path.of("src/test/resources/archunit_store");

	/**
	 * 每条规则的存量违例条数。
	 *
	 * <p>键是规则描述里的特征子串。修掉存量违例后，把对应数字改小并在下方注释追加一行说明。
	 * <b>只允许调小，不允许调大</b>——调大意味着放行了新增违例，那是 CI 该拦住的事。
	 *
	 * <p>变更历史：
	 * <ul>
	 *   <li>2026-07-25 S1 初次冻结：518 / 515 / 12 / 220 / 11 —— <b>数字不准</b>，
	 *       当时 weixin-admin 与 im-server 的 target/classes 是陈旧产物，10 条真实违例没进 baseline。</li>
	 *   <li>2026-07-25 S2 期间全量 clean 编译后重建：519 / 516 / 13 / 227 / 11。
	 *       ArchScope 已补陈旧检测（比对源文件与 class 的 mtime），防止再次冻在错误的代码状态上。</li>
	 *   <li>2026-07-25 S12：新增 R5（吞异常）93 条。同时按 {@code @Inside} 注解豁免服务间
	 *       调用端点后，@SysLog 519→430、@PreAuthorize 516→434 —— 那 89/82 个内部端点
	 *       本就不该要求这两个注解（加 @PreAuthorize 会打断内部调用），此前被错误计为技术债。</li>
	 *   <li>2026-07-25 迁移到 GitHub canonical 线后：@PreAuthorize 273→<b>272</b>。
	 *       <b>这一条下降不是「修好了一个违例」，恰恰相反。</b>
	 *       {@code PhoneController.sendSmsCode} 带了 {@code @Inside(value = false)}
	 *       （忘记密码流程要免登录开放），而 {@link ArchExemptions#isInsideEndpoint} 只判注解<b>存在与否</b>、
	 *       不判 {@code value}，于是把一个「对外完全开放」的端点当成「服务间内部端点」豁免掉了。
	 *       {@code @Inside(value=false)} 的真实语义见 {@code ThirdSessionInterceptor:63} 的注释
	 *       与 {@code BaseSecurityInsideAspect}——只有 {@code value()==true} 才校验 FROM_IN 头，
	 *       {@code false} 表示该端点连 Bearer token 都不要。
	 *       全仓有 <b>6 处</b> {@code @Inside(value=false)} 注解位点（2 处是类级，覆盖约 8 个 handler）：
	 *       mall-api 的 HomeAggregateController / PageDeviseController、
	 *       upms 的 SysUserController（忘记密码）/ PhoneController（短信取码）、
	 *       weixin 的 WxPortalController / WxOpenNotifyController（类级，外部回调）。
	 *       <p><b>2026-08-15 复核更正：现为 7 处</b>，新增 {@code SysTenantController:149} 的
	 *       {@code GET /tenant/brand}（免登录查租户品牌）。这一处是<b>刻意开放</b>、不是缺陷，
	 *       但上面那份清单如果不更新，下次照着它核对的人会把它当成新冒出来的洞。
	 *       即门禁当前正在漏放这一整类最暴露的端点。
	 *       登记值先如实记为 272 反映现状；<b>修 {@code isInsideEndpoint} 判据是独立决策</b>，
	 *       修完这个数字会回升，届时不适用「只降不升」的默认结论，需显式复核。</li>
	 *   <li>2026-07-25 修正 {@code isInsideEndpoint} 判据（改为判 {@code value()}）后：
	 *       @SysLog 109→<b>113</b>、@PreAuthorize 272→<b>274</b>，其余四条不变。
	 *       <b>这是本文件唯一一次「调大」，且是唯一正当的调大理由：判据收紧、看见了原本看不见的存量，
	 *       而不是放行了新增违例。</b>已逐条核对，新增的恰好 6 条：
	 *       <ul>
	 *         <li>缺 @SysLog（4）：{@code SysUserController.forgetPassword}、
	 *             {@code WxOpenNotifyController.callback} / {@code .receiveTicket}、
	 *             {@code WxPortalController.post}</li>
	 *         <li>缺鉴权（2）：{@code PhoneController.sendSmsCode}、
	 *             {@code SysUserController.forgetPassword}</li>
	 *       </ul>
	 *       ⚠️ <b>那 2 条「缺鉴权」大概率不该长期挂在 baseline 里</b>：忘记密码与短信取码是
	 *       <b>有意对外开放</b>的（靠图形验证码 + 频率限制 + 手机号绑定三道闸，见
	 *       {@code SysUserController} 的 javadoc），永远不会补上 {@code @PreAuthorize}，
	 *       放在「存量技术债、将来要还」的 baseline 里语义是错的。
	 *       正确归宿是 {@link ArchExemptions} 里<b>按全限定名显式豁免并写明理由</b>。
	 *       但「永久豁免一个无鉴权端点」是安全判断，不由本次改动单方面决定，
	 *       故先冻进 baseline 保持可见，待复核后再迁移。</li>
	 *   <li>2026-07-25 S9：@Transactional 13→<b>8</b>、removeById 227→<b>225</b>。
	 *       <b>这次是正当的调小——真修好了</b>，与上一条「判据收紧导致调大」性质相反。
	 *       <ul>
	 *         <li>事务 −5：级联删除补 {@code @Transactional(rollbackFor=Exception.class)} ——
	 *             CouponInfo/GoodsCategory/SeckillHall/WxApp 的 removeById 与 Task.delete</li>
	 *         <li>物理删 −2：账号注销与后台删用户从物理删改为软删 ——
	 *             {@code mall.api.UserInfoController.removeById} 改为委托
	 *             {@code UserInfoService.terminated}（软删 + 脱敏），
	 *             {@code mall.admin.UserInfoServiceImpl.removeById} 改为写 {@code del_flag='1'}。
	 *             动因是实测到大量<b>孤儿订单</b>（其中有已支付的）——
	 *             物理删主表不管从表。</li>
	 *       </ul></li>
	 *   <li>2026-07-26 S6：BatchResult 11→<b>3</b>。逐条核实 11 条后，
	 *       <b>8 条走 {@link BatchEndpoints} 的显式豁免</b>（误报 / 有意为之 / 不适用 / 内部上报，
	 *       理由逐条写在那里）——它们永远不会改，挂 baseline 属错误标注。
	 *       剩下 3 条是同一类（其中一条所在的模块开源版没有）：{@code GoodsSpu.saveBatch}、
	 *       {@code MallSystemConfig.updateBatch}。它们的<b>事务缺陷已在本次修掉</b>
	 *       （前置校验 / 补 @Transactional），保留在 baseline 是因为
	 *       「批量导入商品、批量改配置该不该逐条报结果」是尚未定论的产品问题，
	 *       不该被豁免掉从而失去可见性。</li>
	 *   <li>2026-07-26 S8（波次 2/2b）：removeById 225→<b>216</b>、@Transactional 8→<b>7</b>。
	 *       <b>正当的调小</b>。物理删 −9：4 个登记在 {@link PhysicalDeleteRegistry} 的实体
	 *       （ShoppingCart / NewcomerActivitySpu / WxFastRegist / H5PortalDomain）的删除
	 *       改走 Mapper 上的 {@code physicalRemove*} 裸 SQL —— 它们本就该物理删，
	 *       裸 SQL 是把这个意图显式化，不是绕过规则。
	 *       <p>事务 −1 是 {@code H5PortalDomainServiceImpl.createDefault}，
	 *       但它<b>不是被修好的，是规则看不见了</b>：R2 按「不同持久化目标的个数」判定，
	 *       该方法把 {@code this.removeById} 换成 {@code baseMapper.physicalRemoveById} 之后，
	 *       删和插落到同一个 mapper 上，从 2 个目标变成 1 个。风险（删旧域名+插新域名不原子）
	 *       原样存在，已在该方法上补 {@code @Transactional} 直接消掉，
	 *       并给 {@code ArchPredicates.PERSISTENCE_WRITE_PREFIXES} 补了 {@code physicalRemove}
	 *       前缀，避免后续物理删改造继续让 R2 静默缩水。
	 *       <p><b>口径提醒</b>：R2 数的是「不同持久化目标（owner）」而不是「不同表」，
	 *       同一个 mapper 上的多次写永远算 1 个目标。这是它的固有盲区，别把它的
	 *       「无违例」当成「有事务」。</li>
	 *   <li>2026-07-26 S8（波次 5）：@PreAuthorize 274→<b>271</b>、removeById 216→<b>213</b>。
	 *       <b>正当的调小，但既不是「补注解」也不是「改软删」——是把 5 个账本表的写端点整个下线。</b>
	 *       它们全是脚手架顺带生成的，账本没有「后台删一条」的正当业务场景。
	 *       6 条 baseline 逐条对应到被删的方法（无一条是判据变化导致的静默消失）：
	 *       <ul>
	 *         <li>缺鉴权 −3：{@code ChainCommissionRecordController} 的
	 *             {@code save} / {@code updateById} / {@code removeById}。该类<b>既无
	 *             {@code @PreAuthorize} 也无 {@code @ApiLogin}</b>，即任何人都能删佣金记录，
	 *             是本轮下线的首要动因。类中只读端点（{@code getPage} / {@code getById}）保留。</li>
	 *         <li>物理删 −3：上面那个 {@code ChainCommissionRecordController.removeById}，
	 *             加 {@code OrderProfitSharingController.removeById} 与
	 *             {@code DistributionOrderItemController.removeById}
	 *             （后两个有权限码但 {@code sys_role_menu} 实测<b>授权角色数 = 0</b>，从未被授予过）。</li>
	 *       </ul>
	 *       @SysLog 保持 113 不变是对的：被删的 5 个方法本来都带 {@code @SysLog}，
	 *       从来就不在那条 baseline 里。其余三条（事务 / BatchResult / 吞异常）同理不受影响。</li>
	 *   <li>2026-07-26 S8（C1 修复批）：removeById 213→<b>211</b>，其余五条不变。
	 *       给 {@code WxApp} / {@code DistributionUser} / {@code ChainUserRelation} 三个
	 *       <b>主键即业务键（{@code IdType.INPUT}）</b>的实体补物理删——它们都是「删掉再按同一主键重建」，
	 *       而<b>主键做不成部分唯一索引，V80 那批部分索引对这一类完全没有保护</b>
	 *       （{@code distribution_user} / {@code chain_user_relation} 实测唯一索引<b>只有主键一条</b>）。
	 *       只有 2 条 baseline 消失，因为只有这 2 处的<b>被调方法名</b>变了：
	 *       <ul>
	 *         <li>{@code WxOpenAuthController.jump}：{@code wxAppService.removeById(wxApp)} →
	 *             {@code physicalRemoveById(id)}</li>
	 *         <li>{@code WxAppServiceImpl.removeById(Serializable)} 内部：
	 *             {@code baseMapper.deleteById} → {@code baseMapper.physicalRemoveById}</li>
	 *       </ul>
	 *       另外 4 条<b>有意保留</b>：{@code WxAppController.removeById}、
	 *       {@code WxOpenNotifyController.receiveTicket}、{@code DistributionUserController.removeById}、
	 *       {@code ChainUserRelationController.removeById} —— 它们调的仍叫 {@code removeById}，
	 *       改的是 Service 覆盖方法的<b>内部实现</b>（与 ShoppingCart 同款：覆盖点放 Service 层，
	 *       让实体只有一种删除语义）。<b>这正好暴露 R3 的固有盲区：它按被调方法名判定，
	 *       看不见「名字没变但语义已从软删换成物理删」</b>——这也是波次 4 要把判据换成实体侧规则的原因。</li>
	 *   <li>2026-07-26 S8（波次 4）：<b>R3 整条规则连同它 211 条 baseline 一起作废</b>，
	 *       登记项由六条变<b>五条</b>。这不是「修好了 211 条」，是<b>换判据</b>——
	 *       R3 数的是调用点，而调用点根本回答不了「这个实体到底是软删还是物理删」：
	 *       <ul>
	 *         <li><b>假阳性</b>：翻转后 {@code removeById} 本身就是软删，211 条里绝大多数
	 *             压根不是问题，留着只会训练人无视门禁；</li>
	 *         <li><b>假阴性（更要命）</b>：C1 把 {@code DistributionUser} /
	 *             {@code ChainUserRelation} 改成物理删，改的是 Service 覆盖方法的内部实现，
	 *             控制器那句调用仍叫 {@code removeById} —— R3 的数字<b>一动不动</b>。
	 *             也就是说 211 这个数既不代表风险，也不反映进展。</li>
	 *       </ul>
	 *       接替它的是三条实体侧规则 R6/R7/R8（见 {@code ArchGateTest}），判据只看实体自身声明、
	 *       解析失败一律 fail-closed，且<b>目标违例数是 0，故一律不冻结</b>——
	 *       实体的删除语义不存在「存量技术债」这回事，要么有人拍过板，要么就该红。
	 *       这也是本表只剩五条的原因：新规则不进 baseline 体系。</li>
	 *   <li>2026-07-29：@SysLog 113→<b>106</b>、@PreAuthorize 271→<b>264</b>。
	 *       <b>正当的调小</b>，且不是本次改动做的——是 0727 那批 parallel 集成合并
	 *       给 {@code im.controller.admin.SupervisorAdminController} 的 7 个写端点
	 *       （{@code coaching} / {@code endListen} / {@code evaluateRisk} /
	 *       {@code similarCases} / {@code startListen} / {@code suggestedAction} /
	 *       {@code takeover}）补上了方法级 {@code @SysLog} 与鉴权注解。
	 *       两条规则各减同样的 7 条、且是同一批方法，登记值随 store 一起下调。</li>
	 *   <li>2026-08-02：@PreAuthorize 264→<b>261</b>。净 -3，由四件事叠加而成：
	 *       <ul>
	 *         <li><b>-2 真修好</b>：{@code AdminNotificationController.markAsRead / markAllAsRead}
	 *             被补上了鉴权注解（不是本次改的，是之前某次提交顺手修的，store 自动移除）。</li>
	 *         <li><b>-1 迁豁免</b>：{@code TokenController.logout} 移入
	 *             {@code ArchExemptions.AUTH_SERVER_SELF_SERVICE_METHODS}——{@code /token/**}
	 *             整体 permitAll，挂 {@code @PreAuthorize} 会把功能打死，属结构性豁免而非待还的债。</li>
	 *         <li><b>±0 修签名漂移</b>：{@code LabelController.deleteLabel} 与
	 *             {@code LabelGroupController.deleteLabelGroup} 的入参由 {@code Long} 改成
	 *             {@code String}，冻结条目对不上号而被判成「已修复 + 新违例」。已把新签名写回 store。
	 *             <b>这不是抬基线</b>：同类的 create/update 四条一直在册，这两条只是换了个签名的同一笔债。</li>
	 *       </ul>
	 *       同批还清掉了 18 条新违例（im/analytics 类级注解补方法级声明、upms 自助端点补
	 *       {@code isAuthenticated()}），那些是修在代码里、不进 store。</li>
	 *   <li>2026-08-15：@PreAuthorize 261→<b>260</b>。<b>正当的调小——真修好了</b>，
	 *       且不是本次改动做的：{@code mall.admin.ReferralRewardConfigController.update}
	 *       在 {@code 73baf220a}（推广中心死配置收口）里被补上了
	 *       {@code @PreAuthorize("@ato.hasAuthority('mall:distributionconfig:edit')")}，
	 *       store 自动移除该条，但登记值没跟着改，于是门禁从那次提交起就一直是红的。
	 *       <p><b>这正是本测试要抓的东西</b>：修好存量必须显式记一笔，否则「门禁红了」
	 *       会退化成背景噪声——实测它确实在 master 上红了十几天没人处理。</li>
	 *   <li>2026-08-17（匿名写接口补登录校验）：登记项由五条变<b>六条</b>，
	 *       新增 <b>方法级 @ApiLogin = 9</b>；同时 @PreAuthorize 260→<b>255</b>。
	 *       <ul>
	 *         <li><b>为什么要加 R1c</b>：R1b 的判据里认类级 {@code @ApiLogin}
	 *             （{@code method.getOwner().isAnnotatedWith(...)}），<b>而且不判 mustLogin 的值</b>；
	 *             但 {@code ThirdSessionInterceptor} 此前只读方法级注解。两边一叠加就是
	 *             「门禁绿、运行期无鉴权」。{@code POST /mallapi/file/upload}（匿名可往 OSS 传 1GB 文件）
	 *             就是这么活下来的：类上写着 {@code @ApiLogin(mustLogin = false)}，R1b 判它已声明鉴权。
	 *             R1c 只认方法级，且只管 mall-api——后台服务走 Spring Security，不适用这个陷阱。</li>
	 *         <li><b>@PreAuthorize −5 是正当的调小，本次真修好的</b>，逐条对应：
	 *             {@code mall.api.ShoppingCartController.del}、
	 *             {@code mall.api.BargainUserController.removeById}、
	 *             {@code mall.api.OrderInfoController} 的 {@code removeById} / {@code orderCancel} /
	 *             {@code orderReceive}。这 5 个此前既无鉴权也无归属校验，orderId / cartId 都是
	 *             可枚举雪花 ID——匿名即可取消任意未支付订单、确认收货任意待收货订单
	 *             （会推进终态并触发结算/佣金链路）、删任意用户的购物车行。</li>
	 *         <li><b>R1c 的 9 条 baseline 是同一类存量</b>：读语义误用了 POST
	 *             （{@code GaodeMap} ×3、{@code SysDict.getListPage}、
	 *             {@code GoodsTrendRule.getTrendRuleDetail}、{@code CardSpec.sellSpecByIds}、
	 *             {@code WxMaQrCode.getUnlimited}、{@code WxMaShortLink.generateShortLink}、
	 *             {@code WxTemplateMsg.getList}）。改成 GET 或补注解都能还，所以归 baseline
	 *             而不是塞 {@link ArchExemptions}——塞进豁免等于永久放弃。</li>
	 *         <li>另有 19 处在本次直接修在代码里（补方法级 {@code @ApiLogin} + 归属校验），
	 *             13 处登录/注册/取码/验证码入口走 {@link ArchExemptions} 结构性豁免，
	 *             28 处外部回调与 {@code @Inside} 服务间端点复用既有豁免判据。</li>
	 *       </ul></li>
	 *   <li>2026-08-18（后台接口权限补齐）：
	 *       @PreAuthorize 255→<b>146</b>。<b>正当的调小——109 条真修好了</b>，全部是
	 *       补方法级 {@code @PreAuthorize}，没有改判据、没有塞豁免、没有涨基线。
	 *       <ul>
	 *         <li>R6 批三（写端点）+ 批四（读端点）合计给 mall-admin 补了约 70 条，
	 *             codegen 12 条（该服务此前<b>整模块零鉴权</b>，{@code /gen/dsconf} 能增删改
	 *             数据库连接配置；它当前未部署，所以是休眠雷不是现行火），
	 *             pay 1 条（{@code payapplyform/imageUploadV3}，同时补了 {@code UploadValidator}），
	 *             其余来自特定业务域 32 条与 upms 批次。</li>
	 *         <li><b>剩下的 146 条不是「还没做」</b>：支付/微信回调（加鉴权＝收不到通知）、
	 *             pro-app/H5/PC 共用的端点（加后台码＝403 掉终端用户）、
	 *             后台跨页公共数据源、登录令牌与用户自助操作。
	 *             逐条理由见各条目旁的注释。</li>
	 *         <li>判据教训：「后台零调用方」和「只有一个后台页调用」<b>都证明不了</b>
	 *             它是后台专属端点——{@code LargeScreenController} 的 7 条大屏端点
	 *             按这个判据会被误判成安全，实际是 pro-app 在调。必须跨四个前端仓库核对。</li>
	 *       </ul></li>
	 *   <li>2026-08-18（同批，weixin 调用方核对）：@PreAuthorize 146→<b>142</b>。
	 *       weixin 的 10 个「无鉴权注解」端点跨四端 + 后端服务间逐条核过，
	 *       <b>8 条补码、2 条明确不补</b>：
	 *       {@code /weixin/open/auth/goto_auth_url} 与 {@code /jump} 是微信开放平台
	 *       带着 auth_code 重定向过来的落地页，<b>结构上不可能携带 Bearer token</b>，
	 *       且已在 release-urls 白名单里。
	 *       <p>差点判错的一条：{@code wxmaqrcode} / {@code wxmashortlink} 在 h5/pc/pro-app
	 *       里<b>确实有引用</b>，但走的是 {@code /mallapi/...}（mall-api，另一个服务），
	 *       不是这两个 weixin 端点。同 {@code ensuregoods} / {@code tablearea} 一个形态 ——
	 *       <b>看到 C 端有引用先比路径前缀，别急着判"C 端在用不能加码"。</b></li>
	 * </ul>
	 */
	private static final Map<String, Integer> EXPECTED_VIOLATIONS = new LinkedHashMap<>(Map.of(
			// 2026-09-28 客服运营包 ae5c20af3：ImRobotController 的 create / update / delete 补了 @SysLog，106→103
			// （同时 2026-09-29 给资讯站 ZxContentAdminController 的 7 个写端点补了 @SysLog——那 7 条是新增违例，从未进过 baseline，不影响这个数）
			// 2026-10-20 开源分支：删 im / 个推等模块，冻结库里属于已删类的 99 条随之移除（逐条核过类已不存在），103→4
			// 2026-10-20 开源分支阶段 4：删 mall-api / mall-admin 业务功能族，冻结库自动移除 109 条，逐条核过 108 条所属类或方法已不存在、
			// 1 条（WxUserController.loginMp 吞异常）是重写后真的去掉了 try/catch；4→0
			"@SysLog", 0,
			// 2026-09-25 AI 客服修复 P1-5：ImAdminAiController.draft 补了方法级 @PreAuthorize（im:ai:use 或 im:ai:manage），142→141
			// 2026-10-20 开源分支：删 im 控制器（112 条）与 mall-admin 个推控制器（1 条），逐条核过类已不存在，141→28
			// 2026-10-20 开源分支阶段 4（同 @SysLog 那条），28→10
			"@PreAuthorize", 10,
			// 2026-10-06 客服知识库问答卡：AiKnowledgeSegmentServiceImpl.updateKnowledgeSegment 加了事务（先写新向量、最后删旧向量），7→6
			// 2026-10-20 开源分支：3 条所属类随 im 模块删除，6→3
			// 2026-10-20 开源分支阶段 4（同 @SysLog 那条），3→1
			"@Transactional", 1,
			// 2026-10-20 开源分支阶段 4（同 @SysLog 那条），3→2
			"BatchResult", 2,
			// 2026-09-27 IM 链路 v2：WorkflowExecutionService 的执行改走 ImTinyFlowRuntime，旧的吞异常分支随之删除，83→82
			// 2026-10-06 gRPC r3 遗留 WP-A A1：OrderRefundsController.refundOffline 的「退款成功」通知移进服务的到账收口，
			// 控制器里吞通知异常的 try/catch 随之删除，82→81
			// 2026-10-20 开源分支阶段 4（同 @SysLog 那条），81→4
			"ErrorLogRecorder", 4,
			// markerFor 用 contains 匹配，标记必须与其余五条互斥。
			// 不能只写 "@ApiLogin"——R1b 的描述里也有这个词（"@PreAuthorize or @ApiLogin"）。
			// 取 R1c 条件名里的 "method-level @ApiLogin"：R1b 描述不含它，R1c 描述也不含
			// 其余五个标记中的任何一个，双向无歧义。
			// 2026-10-20 开源分支阶段 4（同 @SysLog 那条），9→2
			"method-level @ApiLogin", 2));

	@Test
	@DisplayName("baseline 条目数与规则数一致（无孤儿、无重置）")
	void storeHasExactlyTheExpectedRules() throws IOException {
		Properties rules = loadStoredRules();

		assertEquals(EXPECTED_VIOLATIONS.size(), rules.size(),
				"""
						stored.rules 条目数与登记的规则数不一致。常见原因：
						  1. 改了规则描述（含 because 文案）→ ArchUnit 建了新条目，旧条目成孤儿，
						     该规则的存量违例被重新冻结 = 洗白。删掉孤儿条目并核对违例数。
						  2. 新增/删除了规则 → 同步更新 EXPECTED_VIOLATIONS。
						当前条目：%s""".formatted(rules.stringPropertyNames()));
	}

	@Test
	@DisplayName("各规则存量违例数与登记值一致（只降不升，下降需显式更新）")
	void violationCountsMatchRecordedBaseline() throws IOException {
		Properties rules = loadStoredRules();
		List<String> problems = new ArrayList<>();

		for (String description : rules.stringPropertyNames()) {
			String marker = markerFor(description);
			if (marker == null) {
				problems.add("规则未登记到 EXPECTED_VIOLATIONS：" + description);
				continue;
			}
			Path violationFile = STORE.resolve(rules.getProperty(description));
			if (!Files.isRegularFile(violationFile)) {
				problems.add("规则 [%s] 的 baseline 文件缺失：%s".formatted(marker, violationFile));
				continue;
			}
			long actual = Files.readAllLines(violationFile, StandardCharsets.UTF_8).stream()
					.filter(line -> !line.isBlank())
					.count();
			int expected = EXPECTED_VIOLATIONS.get(marker);
			if (actual != expected) {
				problems.add("规则 [%s] 存量违例 %d 条，登记值 %d 条。%s"
						.formatted(marker, actual, expected,
								actual < expected
										? "已修掉部分存量——请把 EXPECTED_VIOLATIONS 里的数字改成 " + actual
										: "存量增加了，说明 baseline 被重置或规则判据被放宽，不要直接改大数字"));
			}
		}

		if (!problems.isEmpty()) {
			fail(String.join("\n", problems));
		}
	}

	private static String markerFor(String description) {
		return EXPECTED_VIOLATIONS.keySet().stream()
				.filter(description::contains)
				.findFirst()
				.orElse(null);
	}

	private static Properties loadStoredRules() throws IOException {
		Path storedRules = STORE.resolve("stored.rules");
		assertTrue(Files.isRegularFile(storedRules),
				"baseline 索引 " + storedRules.toAbsolutePath() + " 不存在。store 丢失等于门禁归零，"
						+ "不要用 allowStoreCreation=true 重建——那会把存量违例全部洗白。请从 git 恢复。");

		Properties properties = new Properties();
		try (Reader reader = Files.newBufferedReader(storedRules, StandardCharsets.ISO_8859_1)) {
			properties.load(reader);
		}
		return properties;
	}
}
