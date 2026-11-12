package com.infrahub.release.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
@TableName("asset_host_ssh_config")
public class AssetHostSshConfig {

    private Long id;
    private Long hostId;
    private Integer sshPort;
    private String authType;
    private String username;
    private Long passwordSecretRefId;
    private Long privateKeySecretRefId;
    private Long passphraseSecretRefId;
    private Boolean enabled;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
