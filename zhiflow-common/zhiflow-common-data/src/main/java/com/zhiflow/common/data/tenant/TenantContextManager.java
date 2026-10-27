package com.zhiflow.common.data.tenant;

import java.util.function.Supplier;

/**
 * 租户上下文管理工具类
 * <p>
 * 提供显式的租户上下文切换和恢复功能，支持嵌套调用
 * 适用于手动控制租户上下文的场景
 *
 */
public final class TenantContextManager {

    private TenantContextManager() {
        throw new IllegalStateException("Utility class");
    }

    /**
     * 在指定租户上下文中执行操作
     * <p>
     * 执行完成后自动恢复之前的租户上下文
     * 支持嵌套调用，内层调用不会影响外层的上下文状态
     *
     * @param tenantId 目标租户ID
     * @param runnable 要执行的操作
     */
    public static void executeWithTenant(String tenantId, Runnable runnable) {
        String previousTenantId = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            runnable.run();
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /**
     * 在指定租户上下文中执行操作并返回结果
     * <p>
     * 执行完成后自动恢复之前的租户上下文
     * 支持嵌套调用，内层调用不会影响外层的上下文状态
     *
     * @param tenantId 目标租户ID
     * @param supplier 要执行的操作
     * @param <T>      返回值类型
     * @return 操作的返回值
     */
    public static <T> T executeWithTenant(String tenantId, Supplier<T> supplier) {
        String previousTenantId = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.setTenantId(tenantId);
            return supplier.get();
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /**
     * 在忽略租户过滤的上下文中执行操作
     * <p>
     * 清除当前租户上下文，执行操作后恢复之前的租户上下文
     * 适用于超级管理员查询等需要跨租户访问的场景
     * 支持嵌套调用
     *
     * @param runnable 要执行的操作
     */
    public static void executeWithoutTenant(Runnable runnable) {
        String previousTenantId = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.clear();
            runnable.run();
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /**
     * 在忽略租户过滤的上下文中执行操作并返回结果
     * <p>
     * 清除当前租户上下文，执行操作后恢复之前的租户上下文
     * 适用于超级管理员查询等需要跨租户访问的场景
     * 支持嵌套调用
     *
     * @param supplier 要执行的操作
     * @param <T>      返回值类型
     * @return 操作的返回值
     */
    public static <T> T executeWithoutTenant(Supplier<T> supplier) {
        String previousTenantId = TenantContextHolder.getTenantId();
        try {
            TenantContextHolder.clear();
            return supplier.get();
        } finally {
            restoreTenant(previousTenantId);
        }
    }

    /**
     * 恢复租户上下文
     * <p>
     * 先弹出当前上下文（栈操作），然后恢复之前的租户
     * 如果之前存在租户ID，则恢复；否则保持清除状态
     *
     * @param previousTenantId 之前的租户ID，可能为null
     */
    private static void restoreTenant(String previousTenantId) {
        // 先弹出当前上下文（栈操作，对应 setTenantId 的 push）
        TenantContextHolder.pop();

        // 如果有之前的租户，再设置回去
        if (previousTenantId != null) {
            TenantContextHolder.setTenantId(previousTenantId);
        }
    }
}
