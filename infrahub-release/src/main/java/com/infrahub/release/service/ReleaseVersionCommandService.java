package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.CreateReleaseVersionRequest;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.mapper.ReleaseArtifactMapper;
import com.infrahub.release.mapper.ReleaseVersionMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ReleaseVersionCommandService {

    private final ReleaseVersionMapper releaseVersionMapper;
    private final ReleaseArtifactMapper releaseArtifactMapper;

    @Transactional
    public ReleaseVersion create(CreateReleaseVersionRequest request) {
        ReleaseArtifact artifact = releaseArtifactMapper.selectById(request.getArtifactId());
        if (artifact == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "制品不存在");
        }
        ReleaseVersion existing = releaseVersionMapper.selectOne(new LambdaQueryWrapper<ReleaseVersion>()
                .eq(ReleaseVersion::getAppId, request.getAppId())
                .eq(ReleaseVersion::getVersionNo, request.getVersionNo())
                .eq(ReleaseVersion::getIsDeleted, Boolean.FALSE));
        if (existing != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "版本号已存在");
        }

        ReleaseVersion version = new ReleaseVersion();
        version.setAppId(request.getAppId());
        version.setVersionNo(request.getVersionNo().trim());
        version.setGitCommit(trimToNull(request.getGitCommit()));
        version.setBranchName(trimToNull(request.getBranchName()));
        version.setBuildNo(trimToNull(request.getBuildNo()));
        version.setArtifactId(request.getArtifactId());
        try {
            releaseVersionMapper.insert(version);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "版本创建失败：数据库序列可能落后于 release_version.id，请先检查序列健康状态");
        }
        return version;
    }

    private static String trimToNull(String value) {
        return StringUtils.hasText(value) ? value.trim() : null;
    }
}
