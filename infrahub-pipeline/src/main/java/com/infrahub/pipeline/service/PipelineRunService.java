package com.infrahub.pipeline.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.common.context.ApplicationAccess;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.event.PipelineCompletedEvent;
import com.infrahub.pipeline.executor.PipelineStepExecutionResult;
import com.infrahub.pipeline.executor.PipelineStepExecutorRegistry;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.task.TaskExecutor;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.io.IOException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class PipelineRunService extends ServiceImpl<PipelineRunMapper, PipelineRun> {

    private final PipelineDefService pipelineDefService;
    private final PipelineStageService pipelineStageService;
    private final PipelineStepService pipelineStepService;
    private final PipelineStepRunService pipelineStepRunService;
    private final PipelineStepLogStore pipelineStepLogStore;
    private final PipelineStepExecutorRegistry pipelineStepExecutorRegistry;
    private final TaskExecutor pipelineTaskExecutor;
    private final ApplicationEventPublisher eventPublisher;
    private final TransactionTemplate pipelineTransactionTemplate;

    @Transactional
    public PipelineRun runPipeline(Long pipelineId, Long envId, Long versionId, Long releaseId) {
        PipelineRunContext context = prepareRun(pipelineId, envId, versionId, releaseId, null);
        return executeRun(context.run(), context.stages(), context.stepsByStage());
    }

    @Transactional
    public PipelineRun startPipeline(Long pipelineId, Long envId, Long versionId, Long releaseId) {
        return startPipeline(pipelineId, envId, versionId, releaseId, null);
    }

    @Transactional
    public PipelineRun startPipeline(Long pipelineId, Long envId, Long versionId, Long releaseId, Long serviceId) {
        PipelineRunContext context = prepareRun(pipelineId, envId, versionId, releaseId, serviceId);
        submitRunTask(context);
        return context.run();
    }

    private void submitRunTask(PipelineRunContext context) {
        Runnable task = () -> executeRun(context.run(), context.stages(), context.stepsByStage());
        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            try {
                pipelineTaskExecutor.execute(task);
            } catch (RejectedExecutionException exception) {
                markRunFailedAndPublish(context.run(), "流水线执行队列已满，请稍后重试");
            }
            return;
        }
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    pipelineTaskExecutor.execute(task);
                } catch (RejectedExecutionException exception) {
                    markRunFailedAndPublish(context.run(), "流水线执行队列已满，请稍后重试");
                }
            }
        });
    }

    public void recoverInterruptedRuns() {
        List<PipelineRun> runningRuns = lambdaQuery()
                .eq(PipelineRun::getRunStatus, "RUNNING")
                .list();
        for (PipelineRun run : runningRuns) {
            markRunFailedAndPublish(run, "应用重启导致流水线运行中断");
        }
    }

    public void fillVersionOutputs(List<PipelineRun> runs) {
        List<Long> runIds = runs.stream().filter(run -> run.getVersionId() != null).map(PipelineRun::getId).toList();
        if (runIds.isEmpty()) return;
        Map<Long, PipelineRun> outputs = baseMapper.listVersionOutputs(runIds).stream()
                .collect(Collectors.toMap(PipelineRun::getId, run -> run));
        for (PipelineRun run : runs) {
            PipelineRun output = outputs.get(run.getId());
            if (output != null) {
                run.setVersionNo(output.getVersionNo());
                run.setArtifactId(output.getArtifactId());
            }
        }
    }

    private PipelineRunContext prepareRun(Long pipelineId, Long envId, Long versionId, Long releaseId, Long serviceId) {
        PipelineDef pipeline = pipelineDefService.getById(pipelineId);
        if (pipeline == null || Boolean.FALSE.equals(pipeline.getEnabled()) || Boolean.TRUE.equals(pipeline.getIsDeleted())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线不存在或未启用");
        }
        if (serviceId != null && !baseMapper.belongsToApplication(serviceId, pipeline.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务不属于当前应用");
        }
        if (versionId != null && !baseMapper.versionBelongsToApplication(versionId, pipeline.getAppId())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "版本不属于当前应用");
        }

        List<PipelineStage> stages = pipelineStageService.listByPipelineIdOrdered(pipelineId);
        if (stages.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线未配置阶段");
        }

        List<Long> stageIds = stages.stream().map(PipelineStage::getId).toList();
        List<PipelineStep> steps = pipelineStepService.listByStageIdsOrdered(stageIds);
        if (steps.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线未配置步骤");
        }

        Map<Long, List<PipelineStep>> stepsByStage = steps.stream()
                .collect(Collectors.groupingBy(PipelineStep::getStageId));
        validateReleaseSteps(stages, stepsByStage, pipeline.getAppId(), envId, versionId, releaseId, serviceId);

        OffsetDateTime startedAt = OffsetDateTime.now();
        PipelineRun run = new PipelineRun();
        run.setPipelineId(pipelineId);
        run.setAppId(pipeline.getAppId());
        run.setEnvId(envId);
        run.setServiceId(serviceId);
        run.setVersionId(versionId);
        run.setReleaseId(releaseId);
        run.setTriggerBy(UserContextHolder.getUserIdOrDefault(1L));
        run.setTriggerType("MANUAL");
        run.setRunStatus("RUNNING");
        run.setStartTime(startedAt);
        save(run);
        return new PipelineRunContext(run, stages, stepsByStage);
    }

    private void validateReleaseSteps(List<PipelineStage> stages, Map<Long, List<PipelineStep>> stepsByStage,
                                      Long appId, Long envId, Long versionId, Long releaseId, Long serviceId) {
        List<PipelineStep> ordered = stages.stream()
                .flatMap(stage -> stepsByStage.getOrDefault(stage.getId(), List.of()).stream()).toList();
        List<String> executors = ordered.stream().map(PipelineStep::getExecutorType).toList();
        int register = executors.indexOf("REGISTER_ARTIFACT");
        int deploy = executors.indexOf("RELEASE_DEPLOY");
        if (register >= 0 && (versionId != null || releaseId != null || register != executors.lastIndexOf("REGISTER_ARTIFACT"))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "构建流水线只登记一个新版本，请清除已有版本或发布单参数");
        }
        if (register >= 0 && (Boolean.TRUE.equals(ordered.get(register).getContinueOnFail())
                || (deploy >= 0 && Boolean.TRUE.equals(ordered.get(deploy).getContinueOnFail())))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "登记制品或发布部署失败时必须停止流水线");
        }
        if ((deploy < 0 && (register < 0 || serviceId == null)) || releaseId != null) return;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        boolean globalRelease = auth != null && auth.getAuthorities().stream().anyMatch(authority ->
                List.of("RELEASE_EXECUTE", "SYSTEM_ADMIN").contains(authority.getAuthority()));
        if (!globalRelease && !ApplicationAccess.permits(appId, "PIPELINE_DEPLOY")) {
            throw new AccessDeniedException("运行部署流水线需要发布执行权限");
        }
        if (deploy >= 0 && (register < 0 || register > deploy || deploy != executors.lastIndexOf("RELEASE_DEPLOY"))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "部署流水线需先登记制品，再执行一次发布部署步骤");
        }
        if (serviceId == null || envId == null || !baseMapper.hasDeploymentTarget(serviceId, appId, envId)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "请选择当前应用在目标环境下有运行实例的服务");
        }
    }

    private PipelineRun executeRun(PipelineRun run, List<PipelineStage> stages, Map<Long, List<PipelineStep>> stepsByStage) {
        boolean blocked = false;
        String errorMessage = null;
        for (PipelineStage stage : stages) {
            List<PipelineStep> stageSteps = stepsByStage.getOrDefault(stage.getId(), List.of());
            for (PipelineStep step : stageSteps) {
                run.setCurrentStage(stage.getStageName());
                pipelineTransactionTemplate.execute(status -> {
                    updateById(run);
                    return null;
                });

                PipelineStepRun stepRun = buildRunningStepRun(run.getId(), stage, step);
                pipelineTransactionTemplate.execute(status -> {
                    pipelineStepRunService.save(stepRun);
                    return null;
                });

                if (blocked) {
                    finishSkippedStep(stepRun);
                    pipelineTransactionTemplate.execute(status -> {
                        pipelineStepRunService.updateById(stepRun);
                        return null;
                    });
                    continue;
                }

                PipelineStepExecutionResult result;
                try {
                    result = pipelineStepExecutorRegistry.execute(run, step, stepRun);
                    if (result.isSuccess() && "REGISTER_ARTIFACT".equals(step.getExecutorType())) {
                        run.setVersionId(baseMapper.selectById(run.getId()).getVersionId());
                    }
                } catch (RuntimeException exception) {
                    OffsetDateTime failedAt = OffsetDateTime.now();
                    result = PipelineStepExecutionResult.failure(
                            -1,
                            "",
                            firstNonBlank(exception.getMessage(), "流水线步骤执行失败"),
                            stepRun.getStartTime(),
                            failedAt
                    );
                }
                try {
                    pipelineStepLogStore.saveResultIfMissing(stepRun, result.fullLog());
                } catch (IOException exception) {
                    log.warn("Unable to persist output for pipeline step run {}", stepRun.getId(), exception);
                }
                finishExecutedStep(stepRun, result);
                pipelineTransactionTemplate.execute(status -> {
                    pipelineStepRunService.updateById(stepRun);
                    return null;
                });

                if (!result.isSuccess() && !Boolean.TRUE.equals(step.getContinueOnFail())) {
                    blocked = true;
                    errorMessage = firstNonBlank(result.getStderr(), result.getStdout(), "流水线步骤执行失败");
                }
            }
        }

        OffsetDateTime endedAt = OffsetDateTime.now();
        run.setEndTime(endedAt);
        run.setDurationMs(Duration.between(run.getStartTime(), endedAt).toMillis());
        run.setRunStatus(blocked ? "FAILED" : "SUCCESS");
        run.setErrorMessage(blocked ? truncate(errorMessage, 1000) : "");
        pipelineTransactionTemplate.execute(status -> {
            updateById(run);
            eventPublisher.publishEvent(new PipelineCompletedEvent(run.getId(), run.getReleaseId(), run.getRunStatus(), run.getErrorMessage()));
            return null;
        });
        return run;
    }

    private void markRunFailedAndPublish(PipelineRun run, String errorMessage) {
        OffsetDateTime endedAt = OffsetDateTime.now();
        run.setRunStatus("FAILED");
        run.setEndTime(endedAt);
        if (run.getStartTime() != null) {
            run.setDurationMs(Duration.between(run.getStartTime(), endedAt).toMillis());
        }
        run.setErrorMessage(truncate(errorMessage, 1000));
        pipelineTransactionTemplate.execute(status -> {
            updateById(run);
            eventPublisher.publishEvent(new PipelineCompletedEvent(run.getId(), run.getReleaseId(), run.getRunStatus(), run.getErrorMessage()));
            return null;
        });
    }

    private static PipelineStepRun buildRunningStepRun(Long pipelineRunId, PipelineStage stage, PipelineStep step) {
        PipelineStepRun stepRun = new PipelineStepRun();
        stepRun.setPipelineRunId(pipelineRunId);
        stepRun.setStepId(step.getId());
        stepRun.setStageName(stage.getStageName());
        stepRun.setStepName(step.getStepName());
        stepRun.setRunStatus("RUNNING");
        stepRun.setExecutorNode("LOCAL");
        stepRun.setStartTime(OffsetDateTime.now());
        return stepRun;
    }

    private static void finishExecutedStep(PipelineStepRun stepRun, PipelineStepExecutionResult result) {
        OffsetDateTime endedAt = result.getEndedAt() == null ? OffsetDateTime.now() : result.getEndedAt();
        stepRun.setRunStatus(result.isSuccess() ? "SUCCESS" : "FAILED");
        stepRun.setStartTime(result.getStartedAt() == null ? stepRun.getStartTime() : result.getStartedAt());
        stepRun.setEndTime(endedAt);
        stepRun.setDurationMs(Duration.between(stepRun.getStartTime(), endedAt).toMillis());
        stepRun.setLogExcerpt(truncate(result.fullLog(), 4000));
        stepRun.setErrorMessage(result.isSuccess() ? null : truncate(firstNonBlank(result.getStderr(), result.getStdout(), "流水线步骤执行失败"), 1000));
    }

    private static void finishSkippedStep(PipelineStepRun stepRun) {
        OffsetDateTime endedAt = OffsetDateTime.now();
        stepRun.setRunStatus("SKIPPED");
        stepRun.setEndTime(endedAt);
        stepRun.setDurationMs(Duration.between(stepRun.getStartTime(), endedAt).toMillis());
        stepRun.setErrorMessage("前置步骤失败，已跳过");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    public boolean existsByReleaseId(Long releaseId) {
        return lambdaQuery()
                .eq(PipelineRun::getReleaseId, releaseId)
                .exists();
    }

    private record PipelineRunContext(
            PipelineRun run,
            List<PipelineStage> stages,
            Map<Long, List<PipelineStep>> stepsByStage
    ) {
    }
}
