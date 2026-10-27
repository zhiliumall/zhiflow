package com.zhiflow.mall.admin.statistics;

/** Server-resolved scope; never bind this from HTTP input. */
public record BusinessMetricScope(Type type, String shopId) {
    public enum Type { TENANT, SHOP }
    public static BusinessMetricScope tenant() { return new BusinessMetricScope(Type.TENANT, null); }
    public static BusinessMetricScope shop(String shopId) { return new BusinessMetricScope(Type.SHOP, shopId); }
    public boolean isShop() { return type == Type.SHOP; }
}
