package com.infrahub.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.common.entity.SimpleTimeEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("sys_role")
@EqualsAndHashCode(callSuper = true)
public class SysRole extends SimpleTimeEntity {

    private String roleCode;
    private String roleName;
    private String status;
    private String appScope;
}
