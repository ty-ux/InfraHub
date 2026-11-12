package com.infrahub.release.enums;

/**
 * 治理建议类型枚举
 */
public enum SuggestionType {

    /**
     * 扩容建议
     */
    SCALE_OUT,

    /**
     * 检查主机
     */
    CHECK_HOST,

    /**
     * 清理实例
     */
    CLEANUP_INSTANCE,

    /**
     * 调查容量
     */
    INVESTIGATE_CAPACITY
}
