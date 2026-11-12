package com.infrahub.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("pipeline_run")
public class PipelineRun {

    private Long id;
    private Long pipelineId;
    private Long appId;
    private Long envId;
    private Long serviceId;
    private Long versionId;
    @TableField(exist = false)
    private String versionNo;
    @TableField(exist = false)
    private Long artifactId;
    private Long releaseId;
    private Long triggerBy;
    private String triggerType;
    private String runStatus;
    private OffsetDateTime startTime;
    private OffsetDateTime endTime;
    private Long durationMs;
    private String currentStage;
    private String logPath;
    private String errorMessage;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
