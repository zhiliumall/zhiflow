package com.zhiflow.common.core.entity.base;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableLogic;
import com.fasterxml.jackson.annotation.JsonFormat;
import io.swagger.v3.oas.annotations.media.Schema;
import lombok.Data;

import java.io.Serial;
import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 实体基类 - 包含公共字段：delFlag, createTime, updateTime
 *
 */
@Data
public abstract class BaseEntity implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;

	/**
	 * 逻辑删除标记（0：显示；1：隐藏）
	 *
	 * <p><b>S8 软删翻转（2026-07-26）。</b>{@code @TableLogic} 显式写死取值，不依赖
	 * {@code mybatis-plus.global-config.db-config} 的全局默认，避免配置漂移改变语义。
	 *
	 * <p>生效范围 —— 全部继承本类（含 {@code TenantEntity}）的实体：
	 * <ul>
	 *   <li>{@code removeById} / {@code deleteById} → {@code UPDATE ... SET del_flag='1'}；</li>
	 *   <li>MP 自动查询与 {@code update} 的 <b>WHERE</b> 自动追加 {@code del_flag='0'}，
	 *       墓碑行对 {@code getById}/{@code list}/{@code count} 一律不可见；</li>
	 *   <li>{@code updateById} 的 <b>SET</b> 子句里逻辑删字段会被整个剥掉 ——
	 *       手工软删必须走 {@code LambdaUpdateWrapper.set(...)}，否则静默失效；</li>
	 *   <li><b>不影响</b>：XML 手写 SQL、{@code @Select}/{@code @Delete} 注解 SQL
	 *       （物理删逃生舱 {@code physicalRemove*} 即基于此），以及不继承本类的实体。</li>
	 * </ul>
	 *
	 * <p><b>回滚</b>：删掉下面这一行 {@code @TableLogic} 重新发版即可，秒级，无需数据订正。
	 */
	@Schema(description = "逻辑删除标记（0：显示；1：隐藏）")
	@TableField(fill = FieldFill.INSERT)
	@TableLogic(value = "0", delval = "1")
	private String delFlag;

	/**
	 * 创建时间
	 */
	@Schema(description = "创建时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createTime;

	/**
	 * 最后更新时间
	 */
	@Schema(description = "最后更新时间")
	@JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss", timezone = "GMT+8")
	@TableField(fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updateTime;

}
