package com.infrahub.release.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.executor.PipelineStepExecutionResult;
import com.infrahub.pipeline.executor.PipelineStepExecutor;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.mapper.ReleaseArtifactMapper;
import com.infrahub.release.mapper.ReleaseVersionMapper;
import com.infrahub.release.service.ReleaseArtifactUploadService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import com.infrahub.release.entity.RuntimeServiceInstance;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

@Component
@RequiredArgsConstructor
public class RegisterArtifactPipelineStepExecutor implements PipelineStepExecutor {

    private final ObjectMapper objectMapper;
    private final PipelineStepLogStore logStore;
    private final PipelineRunMapper runMapper;
    private final ReleaseVersionMapper versionMapper;
    private final ReleaseArtifactMapper artifactMapper;
    private final ReleaseArtifactUploadService uploadService;
    private final RuntimeServiceInstanceService instanceService;

    @Override
    public String executorType() {
        return "REGISTER_ARTIFACT";
    }

    @Override
    @Transactional
    public PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        OffsetDateTime startedAt = OffsetDateTime.now();
        PipelineRun locked = runMapper.lockById(run.getId());
        if (locked == null || locked.getAppId() == null || !"RUNNING".equals(locked.getRunStatus()) || locked.getReleaseId() != null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "当前流水线运行不能登记构建产物");
        }
        if (locked.getVersionId() != null) {
            ReleaseVersion existing = versionMapper.selectById(locked.getVersionId());
            if (existing == null || !locked.getId().equals(existing.getBuildPipelineRunId())) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "本次运行已绑定其他版本");
            }
            return PipelineStepExecutionResult.success("已登记版本 " + existing.getVersionNo(), startedAt, OffsetDateTime.now());
        }

        ArtifactDescriptor output = readDescriptor(run.getId());
        String versionNo = "run-" + run.getId();
        Long artifactId = registerArtifact(locked, output, versionNo);

        ReleaseVersion version = new ReleaseVersion();
        version.setAppId(locked.getAppId());
        version.setVersionNo(versionNo);
        version.setGitCommit(output.gitCommit());
        version.setBuildNo(run.getId().toString());
        version.setBuildPipelineRunId(run.getId());
        version.setArtifactId(artifactId);
        version.setCreatedBy(locked.getTriggerBy());
        versionMapper.insert(version);

        locked.setVersionId(version.getId());
        runMapper.updateById(locked);
        return PipelineStepExecutionResult.success("已登记版本 " + versionNo + "，制品 #" + artifactId, startedAt, OffsetDateTime.now());
    }

    private Long registerArtifact(PipelineRun run, ArtifactDescriptor output, String versionNo) {
        String name = versionNo + " " + output.artifactName();
        if (output.file() != null) {
            if (run.getServiceId() == null || run.getEnvId() == null) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "上传本地制品需要选择部署服务和环境");
            }
            Path local = resolveLocalFile(run.getId(), output.file());
            List<Long> instanceIds = instanceService.listByServiceAndEnv(run.getServiceId(), run.getEnvId())
                    .stream().map(RuntimeServiceInstance::getId).toList();
            return uploadService.uploadBuildOutput(instanceIds, local, versionNo + "-" + output.checksum().substring(7, 19)
                    + "-" + output.file(), name, output.checksum()).getArtifactId();
        }
        ReleaseArtifact artifact = new ReleaseArtifact();
        artifact.setArtifactType(output.artifactType());
        artifact.setArtifactName(name);
        artifact.setArtifactUri(output.artifactUri());
        artifact.setChecksum(output.checksum());
        artifact.setSizeBytes(output.sizeBytes());
        artifact.setRepositoryType("DOCKER_IMAGE".equals(output.artifactType()) ? "DOCKER_REGISTRY" : "LOCAL_FILE");
        artifactMapper.insert(artifact);
        return artifact.getId();
    }

    private Path resolveLocalFile(Long runId, String name) {
        try {
            Path directory = logStore.artifactDescriptor(runId).getParent().toRealPath();
            Path file = directory.resolve(name).toRealPath();
            if (file.getParent().equals(directory) && Files.isRegularFile(file)) return file;
        } catch (IOException ignored) {
            // Expose only the configured build output name, never local runner paths.
        }
        throw new BizException(ErrorCode.VALIDATION_ERROR, "在本次流水线的产物目录中找不到制品文件 " + name);
    }

    private ArtifactDescriptor readDescriptor(Long runId) {
        ArtifactDescriptor output;
        var path = logStore.artifactDescriptor(runId);
        try (var input = Files.newInputStream(path)) {
            byte[] json = input.readNBytes(8193);
            if (json.length > 8192) throw new IOException("artifact descriptor too large");
            output = objectMapper.readValue(json, ArtifactDescriptor.class);
        } catch (IOException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "未读取到有效的构建制品描述，请检查 INFRAHUB_ARTIFACT_DESCRIPTOR");
        }
        if (output == null || output.artifactType() == null
                || !List.of("JAR", "DOCKER_COMPOSE", "DOCKER_IMAGE").contains(output.artifactType())
                || !matches(output.artifactName(), "[A-Za-z0-9._-]{1,100}")
                || !matches(output.gitCommit(), "[a-fA-F0-9]{40}|[a-fA-F0-9]{64}")
                || !matches(output.checksum(), "sha256:[a-f0-9]{64}")
                || (output.sizeBytes() != null && output.sizeBytes() <= 0)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "构建制品描述的类型、名称、Git 提交或校验值无效");
        }
        if (output.file() != null) {
            String pattern = switch (output.artifactType()) {
                case "JAR" -> "[A-Za-z0-9_-][A-Za-z0-9._-]{0,90}\\.jar";
                case "DOCKER_COMPOSE" -> "[A-Za-z0-9_-][A-Za-z0-9._-]{0,90}\\.compose\\.yml";
                default -> "(?!)";
            };
            if (output.artifactUri() != null || !matches(output.file(), pattern) || output.file().contains("..")) {
                throw new BizException(ErrorCode.VALIDATION_ERROR, "file 必须是本次运行目录中的 .jar 或 .compose.yml 文件名");
            }
            return output;
        }
        boolean validUri = switch (output.artifactType()) {
            case "JAR" -> matches(output.artifactUri(), "/[A-Za-z0-9_./-]{1,495}\\.jar");
            case "DOCKER_COMPOSE" -> matches(output.artifactUri(), "/[A-Za-z0-9_./-]{1,485}\\.compose\\.yml");
            default -> matches(output.artifactUri(), "[a-z0-9./:_-]{1,415}@" + output.checksum());
        };
        if (!validUri || output.artifactUri().contains("/../")) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "构建制品地址必须是目标机器上的制品路径或带摘要的镜像引用");
        }
        return output;
    }

    private boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    public record ArtifactDescriptor(String artifactType, String artifactName, String artifactUri, String file,
                                     String checksum, String gitCommit, Long sizeBytes) {
    }
}
