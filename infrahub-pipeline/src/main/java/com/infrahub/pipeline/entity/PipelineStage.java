package com.infrahub.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("pipeline_stage")
public class PipelineStage {

    private Long id;
    private Long pipelineId;
    private String stageName;
    private Integer stageOrder;
    private Boolean approvalRequired;
    private Integer timeoutSeconds;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
