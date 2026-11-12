package com.infrahub.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("pipeline_step")
public class PipelineStep {

    private Long id;
    private Long stageId;
    private String stepName;
    private String stepType;
    private String executorType;
    private Integer stepOrder;
    private String scriptContent;
    private Integer timeoutSeconds;
    private Integer retryTimes;
    private Boolean continueOnFail;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
