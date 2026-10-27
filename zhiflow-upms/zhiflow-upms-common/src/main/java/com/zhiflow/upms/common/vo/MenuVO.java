package com.zhiflow.upms.common.vo;

import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonFormat;

/**
 * <p>
 * 菜单权限表
 * </p>
 *
 * @author
 */
@Data
public class MenuVO implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 菜单ID
	 */
	private String id;
	/**
	 * 菜单名称
	 */
	private String name;
	/**
	 * 菜单权限标识
	 */
	private String permission;
	/**
	 * 父菜单ID
	 */
	private String parentId;
	/**
	 * 图标
	 */
	private String icon;
	/**
	 * 一个路径
	 */
	private String path;
	/**
	 * VUE页面
	 */
	private String component;
	/**
	 * 排序值
	 */
	private Integer sort;
	/**
	 * 菜单类型 （0菜单 1按钮）
	 */
	private String type;
	/**
	 * 是否缓冲
	 */
	private String keepAlive;
	/**
	 * 创建时间
	 */
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	private LocalDateTime createTime;
	/**
	 * 更新时间
	 */
	private LocalDateTime updateTime;
	/**
	 * 0--正常 1--删除
	 */
	private String delFlag;


	@Override
	public int hashCode() {
		return id.hashCode();
	}

	/**
	 * menuId 相同则相同
	 *
	 * @param obj
	 * @return
	 */
	@Override
	public boolean equals(Object obj) {
		if (obj instanceof MenuVO vO) {
			String targetMenuId = vO.getId();
			return id.equals(targetMenuId);
		}
		return super.equals(obj);
	}
}
