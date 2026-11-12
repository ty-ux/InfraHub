package com.infrahub.release.dto;

import lombok.Data;

@Data
public class DeployConfigItem {

    private String configKey;
    private String configValue;
    private String valueType;
    private Boolean required;
    /** 是否可作为"已配置"计入完整性判定；歧义或仅环境级存在时为 false */
    private Boolean hasValue;
    /** APP = 应用级（运行期实际读取）；ENV = 仅环境级存在（运行期不读取） */
    private String source;
    /** 同一应用/环境下同键存在多条记录：运行期取值不确定，本次不判定为已配置 */
    private Boolean ambiguous;
    /** 同键记录的条数；仅 ambiguous=true 时有值 */
    private Integer duplicateCount;
}
