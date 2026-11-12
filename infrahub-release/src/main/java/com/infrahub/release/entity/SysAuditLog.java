package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("sys_audit_log")
public class SysAuditLog {

    private Long id;
    private Long operatorId;
    private String bizType;
    private Long bizId;
    private String action;
    private String beforeData;
    private String afterData;
    private String result;
    private String ip;
    private OffsetDateTime createdAt;
}
