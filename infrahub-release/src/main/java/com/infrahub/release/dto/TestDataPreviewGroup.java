package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 测试数据预览分组DTO
 * V1.8: 用于按分组展示测试数据治理预览
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestDataPreviewGroup {
    
    private String groupName;
    private String description;
    private List<TestDataPreviewRecord> records;
    private String recommendedAction;
    private boolean protectedEvidence;
}
