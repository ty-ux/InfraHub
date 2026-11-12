package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_version")
public class ReleaseVersion {

    private Long id;
    private Long appId;
    private String versionNo;
    private String gitCommit;
    private String branchName;
    private String buildNo;
    private Long artifactId;
    private Long buildPipelineRunId;
    @TableField(exist = false)
    private Long buildServiceId;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
    private Boolean isDeleted;
}
