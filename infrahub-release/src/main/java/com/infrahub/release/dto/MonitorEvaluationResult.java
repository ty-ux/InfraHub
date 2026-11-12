package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.util.List;

@Data
public class MonitorEvaluationResult {
    private String targetType;
    private Long targetId;
    private int hitCount;
    private List<Long> eventIds;
    private List<HitRuleSummary> hitRules;
    private int recoveredCount;
    private List<Long> recoveredEventIds;

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    public static class HitRuleSummary {
        private Long ruleId;
        private String ruleName;
        private String metricName;
        private String compareOp;
        private BigDecimal thresholdValue;
        private BigDecimal actualValue;
    }
}
