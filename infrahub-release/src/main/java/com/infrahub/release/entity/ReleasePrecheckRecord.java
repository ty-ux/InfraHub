package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.Data;
import java.time.OffsetDateTime;

@Data
@TableName("release_precheck_record")
public class ReleasePrecheckRecord {
    @TableId(type = IdType.AUTO)
    private Long id;

    private String bizType;  // RELEASE_ORDER, ROLLBACK_ORDER
    private Long bizId;
    private Long envId;
    private Long serviceId;
    private Boolean success;
    private String summary;

    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
