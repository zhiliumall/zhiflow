package com.zhiflow.mall.api.util;

public class SchemaContextHolder {

    private static final ThreadLocal<String> SCHEMA_HOLDER = new ThreadLocal<>();

    public static void setSchema(String schema) {
        SCHEMA_HOLDER.set(schema);
    }

    public static String getSchema() {
        return SCHEMA_HOLDER.get();
    }

    public static void clearSchema() {
        SCHEMA_HOLDER.remove();
    }

    public static <T> T executeWithSchema(String schema, java.util.function.Supplier<T> query) {
        try {
            setSchema(schema);
            return query.get();
        } finally {
            clearSchema();
        }
    }
}
