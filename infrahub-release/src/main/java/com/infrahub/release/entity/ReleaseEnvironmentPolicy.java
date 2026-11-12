package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.LocalTime;
import java.time.OffsetDateTime;

@Data
@TableName("release_environment_policy")
public class ReleaseEnvironmentPolicy {
    @TableId(type = IdType.AUTO)
    private Long id;

    private Long envId;
    private String action;  // EXECUTE_RELEASE, EXECUTE_ROLLBACK
    private String riskLevel;  // LOW, MEDIUM, HIGH, CRITICAL, ALL
    private Boolean enabled;
    private Boolean approvalRequired;
    private Boolean precheckRequired;
    private Boolean blocked;
    private Boolean freezeWindowEnabled;
    private LocalTime freezeWindowStart;
    private LocalTime freezeWindowEnd;
    private String freezeWindowDays;  // "1,2,3,4,5" 表示周一到周五
    private String description;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
