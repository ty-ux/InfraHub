package com.infrahub.system.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.infrahub.common.entity.SimpleTimeEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@TableName("sys_enum")
@EqualsAndHashCode(callSuper = true)
public class SysEnum extends SimpleTimeEntity {

    private String enumType;
    private String enumCode;
    private String enumName;
    private Integer sortOrder;
    private String status;
}
