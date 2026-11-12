package com.infrahub.pipeline.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("pipeline_def")
public class PipelineDef {

    private Long id;
    private String pipelineCode;
    private String pipelineName;
    private Long appId;
    private String triggerType;
    private String pipelineType;
    private Boolean enabled;
    private Integer version;
    private String remark;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
    private Boolean isDeleted;
}
