package com.zhiflow.mall.api.config;

import com.zhiflow.mall.api.util.SchemaContextHolder;
import org.apache.ibatis.executor.statement.StatementHandler;
import org.apache.ibatis.plugin.*;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.Statement;
import java.util.Properties;
import java.util.regex.Pattern;

/**
 * 【死代码（dormant），留待独立清理批删除——取证 2026-07-05】
 * <p>
 * {@link SchemaContextHolder#setSchema}/{@code executeWithSchema} 全仓零生产调用方，
 * {@code getSchema()} 恒返回 null，{@code if (schema != null)} 永不进入 → 本拦截器从不发出任何 SET。
 * 每条 SQL 前的 SET search_path 实际全部来自 common-data 的
 * {@code com.zhiflow.common.data.mybatis.SchemaInterceptor}。
 * 本批（共享框架性能批）明确不删本类，勿在此叠加逻辑。
 */
@Component
@Intercepts({
    @Signature(type = StatementHandler.class, method = "prepare", args = {Connection.class, Integer.class})
})
public class CrossSchemaInterceptor implements Interceptor {

    private static final Pattern SCHEMA_PATTERN = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    @Override
    public Object intercept(Invocation invocation) throws Throwable {
        String schema = SchemaContextHolder.getSchema();
        try {
            if (schema != null) {
                if (!SCHEMA_PATTERN.matcher(schema).matches()) {
                    throw new IllegalArgumentException("Invalid schema name: " + schema);
                }
                Connection connection = (Connection) invocation.getArgs()[0];
                try (Statement stmt = connection.createStatement()) {
                    stmt.execute("SET search_path TO " + schema + ", public");
                }
            }
            return invocation.proceed();
        } finally {
            SchemaContextHolder.clearSchema();
        }
    }

    @Override
    public Object plugin(Object target) {
        return Plugin.wrap(target, this);
    }

    @Override
    public void setProperties(Properties properties) {
    }
}
