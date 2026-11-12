package com.infrahub.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.common.entity.SimpleTimeEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("sys_role_permission")
@EqualsAndHashCode(callSuper = true)
public class SysRolePermission extends SimpleTimeEntity {

    private Long roleId;
    private Long permissionId;
}
