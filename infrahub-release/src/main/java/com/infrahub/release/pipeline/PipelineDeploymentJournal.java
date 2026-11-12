package com.infrahub.release.pipeline;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.release.entity.ReleaseOrder;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.service.ReleaseOrderService;
import com.infrahub.release.service.ReleaseVersionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PipelineDeploymentJournal {

    private final PipelineRunMapper runMapper;
    private final ReleaseOrderService releaseOrderService;
    private final ReleaseVersionService versionService;

    @Transactional
    public Long attachDeployment(PipelineRun run) {
        PipelineRun locked = runMapper.lockById(run.getId());
        if (locked == null || !"RUNNING".equals(locked.getRunStatus()) || locked.getReleaseId() != null
                || locked.getServiceId() == null || locked.getVersionId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线部署缺少本次构建的版本或目标服务");
        }
        ReleaseVersion version = versionService.getById(locked.getVersionId());
        if (version == null || !locked.getId().equals(version.getBuildPipelineRunId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "只能部署本次流水线产出的版本");
        }

        ReleaseOrder journal = new ReleaseOrder();
        journal.setOrderNo("PIPELINE-" + locked.getId());
        journal.setAppId(locked.getAppId());
        journal.setEnvId(locked.getEnvId());
        journal.setServiceId(locked.getServiceId());
        journal.setVersionId(locked.getVersionId());
        journal.setCreatedBy(locked.getTriggerBy());
        if (!releaseOrderService.createReleaseOrder(journal)) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "流水线部署记录创建失败");
        }
        locked.setReleaseId(journal.getId());
        runMapper.updateById(locked);
        return journal.getId();
    }
}
