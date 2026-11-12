package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.mapper.ReleaseArtifactMapper;
import org.springframework.stereotype.Service;

@Service
public class ReleaseArtifactService extends ServiceImpl<ReleaseArtifactMapper, ReleaseArtifact> {
}
