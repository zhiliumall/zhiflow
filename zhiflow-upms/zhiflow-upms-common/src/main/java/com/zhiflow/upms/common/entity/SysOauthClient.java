package com.zhiflow.upms.common.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.io.Serial;
import java.io.Serializable;

/**
 * <p>
 * 客户端信息
 * </p>
 *
 * @author
 */
@Data
public class SysOauthClient implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 客户端ID
	 */
	@NotNull(message = "客户端ID不能为空")
	@TableId(type = IdType.INPUT)
	private String id;

	/**
	 * 客户端密钥
	 */
	@NotNull(message = "客户端密钥 不能为空")
	private String clientSecret;

	/**
	 * 资源ID
	 */
	private String resourceIds;

	/**
	 * 作用域
	 */
	@NotNull(message = "作用域 不能为空")
	private String scope;

	/**
	 * 授权方式（A,B,C）
	 */
	private String authorizedGrantTypes;

	private String webServerRedirectUri;

	private String authorities;

	/**
	 * 请求令牌有效时间
	 */
	private Integer accessTokenValidity;

	/**
	 * 刷新令牌有效时间
	 */
	private Integer refreshTokenValidity;

	/**
	 * 扩展信息
	 */
	private String additionalInformation;

	/**
	 * 是否自动放行
	 */
	private String autoapprove;

}
