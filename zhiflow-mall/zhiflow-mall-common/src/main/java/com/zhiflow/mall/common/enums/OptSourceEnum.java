package com.zhiflow.mall.common.enums;

import com.baomidou.mybatisplus.annotation.IEnum;

/**
 * 操作来源枚举
 */
public enum OptSourceEnum implements IEnum<String> {
    USER("用户"),
    SELLER("商家"),
    SYSTEM("系统");

    private final String value;

    OptSourceEnum(String value) {
        this.value = value;
    }

    @Override
    public String getValue() {
        return value;
    }

    public static OptSourceEnum getByName(String name) {
        for (OptSourceEnum item : values()) {
            if (item.name().equals(name)) {
                return item;
            }
        }
        return defaultSource();
    }

    public static OptSourceEnum defaultSource() {
        return SYSTEM;
    }

    public static OptSourceEnum defaultSource(OptSourceEnum recordSource) {
        return recordSource == null ? SYSTEM : recordSource;
    }
}