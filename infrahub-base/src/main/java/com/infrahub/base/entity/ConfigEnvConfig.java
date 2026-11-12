package com.infrahub.base.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("config_env_config")
public class ConfigEnvConfig {

    private Long id;
    private Long envId;
    private Long appId;
    private Long groupId;
    private String configKey;
    private String configValue;
    private String valueType;
    private Long secretRefId;
    private Integer versionNo;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private Long updatedBy;
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private OffsetDateTime updatedAt;
    private Boolean isDeleted;
}
