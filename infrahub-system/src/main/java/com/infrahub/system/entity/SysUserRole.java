package com.infrahub.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.common.entity.SimpleTimeEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("sys_user_role")
@EqualsAndHashCode(callSuper = true)
public class SysUserRole extends SimpleTimeEntity {

    private Long userId;
    private Long roleId;
}
