package com.infrahub.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.infrahub.common.entity.SimpleTimeEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.OffsetDateTime;

@Data
@TableName("sys_user")
@EqualsAndHashCode(callSuper = true)
public class SysUser extends SimpleTimeEntity {

    private String username;
    private String displayName;
    private String email;
    private String mobile;
    private String authType;
    @JsonIgnore
    private String passwordHash;
    private String status;
    private OffsetDateTime lastLoginAt;
}
