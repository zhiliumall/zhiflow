package com.zhiflow.common.log.config;

import lombok.Getter;
import lombok.Setter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/**
 * 日志配置类
 *
 * @author 辰
 * @date 2023年10月27日11:40:19
 */
@Getter
@Setter
@ConfigurationProperties(BaseLogProperties.PREFIX)
public class BaseLogProperties {

	public static final String PREFIX = "security.log";

	/**
	 * 开启日志记录
	 */
	private boolean enabled = true;

	/**
	 * 按字段名（不分大小写）打码的字段。
	 *
	 * <p>2026-09-27 增补凭证类字段：IM 模型配置的新增 / 修改 / 连通测试请求体带 {@code apiKey} 明文，
	 * 此前会原样写进 {@code sys_log}（默认名单只有 password/mobile/idcard/phone）。
	 * 自由文本（聊天正文、原因说明）不靠这里——字段名猜不全，敏感接口应在 {@code @SysLog} 上关掉请求体采集。
	 */
	@Value("${security.log.exclude-fields:password,mobile,idcard,phone,apiKey,api_key,apiSecret,secret,"
			+ "webhookSecret,accessToken,access_token,refreshToken,token,thirdSession,third_session}")
	private List<String> excludeFields;

	/**
	 * 请求报文最大存储长度
	 */
	private Integer maxLength = 2000;

	/**
	 * 采集 @RequestBody / @PathVariable 入参（S2 R2）。
	 *
	 * <p>关掉后 params 退回改造前的「仅 query 参数」行为。留这个开关是因为 body 采集
	 * 会把业务入参对象序列化进日志，若某个接口的入参含超大字段或不可序列化对象，
	 * 可以先关掉止血再定位。
	 */
	private boolean captureBody = true;

	/**
	 * 记录失败操作（S2 R1）。
	 *
	 * <p>关掉后异常路径退回改造前行为（不留痕）。仅在失败留痕本身出问题时用于止血。
	 */
	private boolean captureFailure = true;

	/**
	 * 上报失败时的本地兜底落盘（S2 R3）。
	 *
	 * <p>关掉后落库失败仅打日志、不写兜底文件。
	 */
	private boolean fallbackToFile = true;

}
