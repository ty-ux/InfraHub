package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.mapper.AssetHostSshConfigMapper;
import org.springframework.stereotype.Service;

@Service
public class AssetHostSshConfigService extends ServiceImpl<AssetHostSshConfigMapper, AssetHostSshConfig> {

    public AssetHostSshConfig getEnabledByHostId(Long hostId) {
        return lambdaQuery()
                .eq(AssetHostSshConfig::getHostId, hostId)
                .eq(AssetHostSshConfig::getEnabled, Boolean.TRUE)
                .one();
    }
}
