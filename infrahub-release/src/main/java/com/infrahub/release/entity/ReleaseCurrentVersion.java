package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("release_current_version")
public class ReleaseCurrentVersion {

    private Long id;
    private Long envId;
    private Long serviceId;
    private Long versionId;
    private Long sourceReleaseOrderId;
    private Long sourceRollbackOrderId;
    private OffsetDateTime confirmedAt;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
}
