package com.infrahub.base.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("config_secret_ref")
public class ConfigSecretRef {

    private Long id;
    private String secretKey;
    private String secretProvider;
    private String secretPath;
    private String description;
    @TableField(fill = FieldFill.INSERT)
    private OffsetDateTime createdAt;
}
