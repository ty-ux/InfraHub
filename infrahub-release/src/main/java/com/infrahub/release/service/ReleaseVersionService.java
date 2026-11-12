package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.mapper.ReleaseVersionMapper;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class ReleaseVersionService extends ServiceImpl<ReleaseVersionMapper, ReleaseVersion> {
    public Long findBuildServiceId(Long runId) {
        return runId == null ? null : baseMapper.findBuildServiceId(runId);
    }

    public List<ReleaseVersion> listRollbackVersions() {
        return baseMapper.listRollbackVersions();
    }
}
