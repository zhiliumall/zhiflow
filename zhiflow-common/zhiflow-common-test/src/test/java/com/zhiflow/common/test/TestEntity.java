package com.zhiflow.common.test;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 基建自测用的样例实体。
 *
 * <p>刻意做成两层继承 + 含 {@code String[]} 字段，对齐本项目实体的真实形状
 * （业务实体普遍 {@code extends TenantEntity extends BaseEntity}，
 * 且 {@code goods_spu.label} / {@code goods_spu.platform} 是 {@code String[]}）——
 * 基建要是只在单层扁平 POJO 上验过，到了真实体上会翻车。
 */
@Data
@EqualsAndHashCode(callSuper = true)
@TableName("test_entity")
public class TestEntity extends TestBaseEntity {

	private String id;

	private String name;

	private String status;

	private Integer sort;

	private BigDecimal amount;

	private String[] labels;

	private LocalDateTime effectiveTime;
}
