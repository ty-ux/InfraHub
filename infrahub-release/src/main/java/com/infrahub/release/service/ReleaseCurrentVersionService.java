package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.ReleaseCurrentVersion;
import com.infrahub.release.mapper.ReleaseCurrentVersionMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;

@Service
public class ReleaseCurrentVersionService extends ServiceImpl<ReleaseCurrentVersionMapper, ReleaseCurrentVersion> {

    public void markReleaseVersion(Long envId, Long serviceId, Long versionId, Long releaseOrderId) {
        ReleaseCurrentVersion currentVersion = lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, envId)
                .eq(ReleaseCurrentVersion::getServiceId, serviceId)
                .one();
        if (currentVersion == null) {
            currentVersion = new ReleaseCurrentVersion();
            currentVersion.setEnvId(envId);
            currentVersion.setServiceId(serviceId);
        }
        currentVersion.setVersionId(versionId);
        currentVersion.setSourceReleaseOrderId(releaseOrderId);
        currentVersion.setSourceRollbackOrderId(null);
        currentVersion.setConfirmedAt(OffsetDateTime.now());
        saveOrUpdate(currentVersion);
    }

    public void markRollbackVersion(Long envId, Long serviceId, Long versionId, Long rollbackOrderId) {
        ReleaseCurrentVersion currentVersion = lambdaQuery()
                .eq(ReleaseCurrentVersion::getEnvId, envId)
                .eq(ReleaseCurrentVersion::getServiceId, serviceId)
                .one();
        if (currentVersion == null) {
            currentVersion = new ReleaseCurrentVersion();
            currentVersion.setEnvId(envId);
            currentVersion.setServiceId(serviceId);
        }
        currentVersion.setVersionId(versionId);
        currentVersion.setSourceRollbackOrderId(rollbackOrderId);
        currentVersion.setConfirmedAt(OffsetDateTime.now());
        saveOrUpdate(currentVersion);
    }
}
