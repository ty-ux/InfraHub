package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测试数据清理SQL响应DTO
 * V1.9: 包含生成的安全清理SQL脚本
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TestDataCleanupSqlResponse {
    
    private String fileName;
    private LocalDateTime generatedAt;
    private boolean protectedEvidenceExcluded;
    private String sql;
}
