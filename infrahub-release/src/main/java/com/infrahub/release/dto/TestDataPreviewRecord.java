package com.infrahub.release.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 测试数据预览记录DTO
 * V1.8: 用于展示单个数据记录的基本信息
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class TestDataPreviewRecord {
    
    private Long id;
    private String name;
    private String type;
    private LocalDateTime createdAt;
    private String description;
}
