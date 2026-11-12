package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;

@Data
@TableName("release_rollback_order")
public class ReleaseRollbackOrder {

    private Long id;
    private String rollbackNo;
    private Long releaseOrderId;
    private Long serviceId;
    private Long envId;
    private String targetInstanceIds;
    private Long fromVersionId;
    private Long toVersionId;
    private String reason;
    private String status;
    @TableField(fill = FieldFill.INSERT)
    private Long createdBy;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;

    public Set<Long> selectedInstanceIds() {
        return targetInstanceIds == null ? Set.of()
                : Arrays.stream(targetInstanceIds.split(",")).map(Long::valueOf).collect(Collectors.toSet());
    }
}
