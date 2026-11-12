package com.infrahub.release.enums;

/**
 * 资源池风险等级枚举
 * order 字段用于比较风险高低，数值越大风险越高
 */
public enum RiskLevel {

    NORMAL(0),
    UNKNOWN(1),
    WARNING(2),
    CRITICAL(3);

    private final int order;

    RiskLevel(int order) {
        this.order = order;
    }

    public int getOrder() {
        return order;
    }

    /**
     * 返回两个风险等级中较高的那个
     */
    public static RiskLevel max(RiskLevel a, RiskLevel b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.getOrder() >= b.getOrder() ? a : b;
    }
}