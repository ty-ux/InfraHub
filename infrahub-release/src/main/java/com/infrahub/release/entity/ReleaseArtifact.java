package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_artifact")
public class ReleaseArtifact {

    private Long id;
    private String artifactType;
    private String artifactName;
    private String artifactUri;
    private String checksum;
    private Long sizeBytes;
    private String repositoryType;
    private String repositoryProject;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
