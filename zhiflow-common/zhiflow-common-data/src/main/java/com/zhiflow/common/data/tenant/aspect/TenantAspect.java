package com.zhiflow.common.data.tenant.aspect;

import com.zhiflow.common.core.constant.CommonConstants;
import com.zhiflow.common.data.tenant.TenantConfigProperties;
import com.zhiflow.common.data.tenant.TenantContextHolder;
import com.zhiflow.common.data.tenant.annotation.IgnoreTenant;
import com.zhiflow.common.data.tenant.annotation.TenantScope;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.core.annotation.AnnotationUtils;

import java.lang.reflect.Method;

/**
 * 租户上下文切换 AOP 切面
 * <p>
 * 提供注解驱动的租户上下文切换功能，支持：
 * <ul>
 *     <li>{@link IgnoreTenant} - 跳过租户过滤（清除上下文）</li>
 *     <li>{@link TenantScope} - 切换到指定租户上下文</li>
 * </ul>
 * <p>
 * 支持方法级和类级注解，方法级注解优先于类级注解。
 * 支持嵌套调用，内层调用完成后自动恢复外层的上下文状态。
 * <p>
 * <b>注意：</b>Spring AOP 基于代理，同类内的自调用（self-invocation）不会被拦截。
 * 例如：在同一个 bean 内通过 this.method() 调用不会触发切面。
 * <b>注意：</b>异步边界不会自动传播租户上下文，需显式处理。
 *
 * @see IgnoreTenant
 * @see TenantScope
 */
@Slf4j
@Aspect
public class TenantAspect {

    private final TenantConfigProperties tenantConfigProperties;

    public TenantAspect(TenantConfigProperties tenantConfigProperties) {
        this.tenantConfigProperties = tenantConfigProperties;
    }

    /**
     * 处理 {@link IgnoreTenant} 注解的方法/类
     * <p>
     * 使用 @annotation + @within 组合，支持方法级和类级注解
     * <p>
     * <b>安全规则：</b>@IgnoreTenant 仅对系统租户（"-1"）生效，普通租户调用时注解不生效，仍保持租户过滤。
     * 这确保了普通用户无法通过 @IgnoreTenant 绕过租户隔离。
     */
    @Around("@annotation(com.zhiflow.common.data.tenant.annotation.IgnoreTenant) || " +
            "@within(com.zhiflow.common.data.tenant.annotation.IgnoreTenant)")
    public Object handleIgnoreTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!tenantConfigProperties.isAnnotationDrivenEnabled()) {
            return joinPoint.proceed();
        }

        // 解析注解（方法级优先于类级）
        IgnoreTenant ignoreTenant = resolveIgnoreTenantAnnotation(joinPoint);

        if (ignoreTenant == null) {
            // 没有找到注解，直接执行
            return joinPoint.proceed();
        }

        // 保存当前租户上下文
        String previousTenantId = TenantContextHolder.getTenantId();

        // 安全检查：@IgnoreTenant 仅对系统租户生效
        // 普通租户调用时，注解不生效，保持原有租户过滤
        if (!isSystemTenant(previousTenantId)) {
            if (log.isDebugEnabled()) {
                log.debug("[IgnoreTenant] 普通租户调用，注解不生效，保持租户过滤，租户ID: {}, 方法: {}", 
                    previousTenantId, getMethodDescription(joinPoint));
            }
            // 不清除租户上下文，直接执行，保持租户过滤
            return joinPoint.proceed();
        }

        if (log.isDebugEnabled()) {
            log.debug("[IgnoreTenant] 系统租户调用，清除租户上下文，方法: {}", getMethodDescription(joinPoint));
        }

        try {
            // 清除租户上下文以跳过过滤
            TenantContextHolder.clear();
            return joinPoint.proceed();
        } finally {
            // 恢复之前的租户上下文
            restoreTenantContext(previousTenantId, "IgnoreTenant");
        }
    }

    /**
     * 判断是否为系统租户
     *
     * @param tenantId 租户ID
     * @return true 表示是系统租户（"-1"），false 表示普通租户
     */
    private boolean isSystemTenant(String tenantId) {
        return CommonConstants.SYSTEM_TENANT_ID.equals(tenantId);
    }

    /**
     * 处理 {@link TenantScope} 注解的方法/类
     * <p>
     * 使用 @annotation + @within 组合，支持方法级和类级注解
     */
    @Around("@annotation(com.zhiflow.common.data.tenant.annotation.TenantScope) || " +
            "@within(com.zhiflow.common.data.tenant.annotation.TenantScope)")
    public Object handleTenantScope(ProceedingJoinPoint joinPoint) throws Throwable {
        if (!tenantConfigProperties.isAnnotationDrivenEnabled()) {
            return joinPoint.proceed();
        }

        // 解析注解（方法级优先于类级）
        TenantScope tenantScope = resolveTenantScopeAnnotation(joinPoint);

        if (tenantScope == null) {
            // 没有找到注解，直接执行
            return joinPoint.proceed();
        }

        // 保存当前租户上下文
        String previousTenantId = TenantContextHolder.getTenantId();
        String targetTenantId = tenantScope.value();

        if (log.isDebugEnabled()) {
            log.debug("[TenantScope] 切换租户上下文: {} -> {}, 方法: {}",
                    previousTenantId, targetTenantId, getMethodDescription(joinPoint));
        }

        try {
            // 设置目标租户上下文
            TenantContextHolder.setTenantId(targetTenantId);
            return joinPoint.proceed();
        } finally {
            // 恢复之前的租户上下文
            restoreTenantContext(previousTenantId, "TenantScope");
        }
    }

    /**
     * 解析 {@link IgnoreTenant} 注解
     * <p>
     * 方法级注解优先于类级注解
     *
     * @param joinPoint 切点
     * @return 注解实例，如果不存在则返回 null
     */
    private IgnoreTenant resolveIgnoreTenantAnnotation(ProceedingJoinPoint joinPoint) {
        // 1. 优先查找方法级注解
        Method method = getMethod(joinPoint);
        if (method != null) {
            IgnoreTenant methodAnnotation = AnnotationUtils.findAnnotation(method, IgnoreTenant.class);
            if (methodAnnotation != null) {
                return methodAnnotation;
            }
        }

        // 2. 查找类级注解
        Class<?> targetClass = joinPoint.getTarget().getClass();
        return AnnotationUtils.findAnnotation(targetClass, IgnoreTenant.class);
    }

    /**
     * 解析 {@link TenantScope} 注解
     * <p>
     * 方法级注解优先于类级注解
     *
     * @param joinPoint 切点
     * @return 注解实例，如果不存在则返回 null
     */
    private TenantScope resolveTenantScopeAnnotation(ProceedingJoinPoint joinPoint) {
        // 1. 优先查找方法级注解
        Method method = getMethod(joinPoint);
        if (method != null) {
            TenantScope methodAnnotation = AnnotationUtils.findAnnotation(method, TenantScope.class);
            if (methodAnnotation != null) {
                return methodAnnotation;
            }
        }

        // 2. 查找类级注解
        Class<?> targetClass = joinPoint.getTarget().getClass();
        return AnnotationUtils.findAnnotation(targetClass, TenantScope.class);
    }

    /**
     * 获取当前执行的具体实现方法
     * <p>
     * 从目标类中解析方法，避免 MethodSignature#getMethod() 返回接口方法的问题
     *
     * @param joinPoint 切点
     * @return 具体实现类的方法对象，如果无法获取则返回 null
     */
    private Method getMethod(ProceedingJoinPoint joinPoint) {
        if (!(joinPoint.getSignature() instanceof MethodSignature signature)) {
            return null;
        }
        Method interfaceMethod = signature.getMethod();
        if (interfaceMethod == null) {
            return null;
        }
        // 从具体实现类中查找方法，确保能找到实现类上的注解
        Class<?> targetClass = joinPoint.getTarget().getClass();
        try {
            return targetClass.getMethod(interfaceMethod.getName(), interfaceMethod.getParameterTypes());
        } catch (NoSuchMethodException _) {
            // 接口方法在实现类中不存在（罕见），回退到接口方法
            return interfaceMethod;
        }
    }

    /**
     * 获取方法描述信息（用于日志）
     *
     * @param joinPoint 切点
     * @return 方法描述字符串
     */
    private String getMethodDescription(ProceedingJoinPoint joinPoint) {
        String className = joinPoint.getTarget().getClass().getSimpleName();
        String methodName = joinPoint.getSignature().getName();
        return className + "." + methodName + "()";
    }

    /**
     * 恢复租户上下文
     * <p>
     * 先弹出当前上下文（栈操作），然后恢复之前的租户
     * 如果之前存在租户ID，则恢复；否则保持清除状态
     * 这确保了嵌套调用和异常路径都能正确恢复状态
     *
     * @param previousTenantId 之前的租户ID，可能为 null
     * @param annotationType   注解类型名称（用于日志）
     */
    private void restoreTenantContext(String previousTenantId, String annotationType) {
        // 先弹出当前上下文（栈操作，对应 setTenantId 的 push）
        TenantContextHolder.pop();

        // 如果有之前的租户，再设置回去
        if (previousTenantId != null) {
            if (log.isDebugEnabled()) {
                log.debug("[{}] 恢复租户上下文: {}", annotationType, previousTenantId);
            }
            TenantContextHolder.setTenantId(previousTenantId);
        } else {
            if (log.isDebugEnabled()) {
                log.debug("[{}] 清除租户上下文（之前无租户）", annotationType);
            }
        }
    }
}
