package com.infrahub.release.service;

import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.infra.ssh.SshFileTransferService;
import com.infrahub.infra.ssh.SshFileUploadRequest;
import com.infrahub.release.dto.ReleaseArtifactUploadResponse;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.ReleaseArtifactMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.format.DateTimeFormatter;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class ReleaseArtifactUploadService {

    private static final long MAX_FILE_SIZE_BYTES = 200L * 1024L * 1024L;
    private static final DateTimeFormatter TEMP_SUFFIX_FORMATTER = DateTimeFormatter.ofPattern("yyyyMMddHHmmssSSS");

    private final RuntimeServiceInstanceService runtimeServiceInstanceService;
    private final AssetHostService assetHostService;
    private final AssetHostSshConfigService assetHostSshConfigService;
    private final BaseServiceEntityService baseServiceEntityService;
    private final DeployConfigService deployConfigService;
    private final SecretValueResolver secretValueResolver;
    private final SshFileTransferService sshFileTransferService;
    private final ReleaseArtifactMapper releaseArtifactMapper;

    @Transactional
    public ReleaseArtifactUploadResponse upload(Long runtimeServiceInstanceId, MultipartFile file, String artifactName) {
        String fileName = validateFile(file);
        String checksum = checksum(file::getInputStream);
        return store(List.of(resolveTarget(runtimeServiceInstanceId, fileName)), file::getInputStream, fileName, artifactName, checksum, file.getSize());
    }

    @Transactional
    public ReleaseArtifactUploadResponse uploadBuildOutput(List<Long> instanceIds, Path file, String fileName, String artifactName, String expectedChecksum) {
        long size;
        try {
            size = Files.size(file);
        } catch (IOException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "无法读取流水线生成的制品文件");
        }
        if (size <= 0 || size > MAX_FILE_SIZE_BYTES || instanceIds.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线制品为空、超过 200MB，或没有上传目标实例");
        }
        InputSource source = () -> Files.newInputStream(file);
        String checksum = checksum(source);
        if (!checksum.equals(expectedChecksum)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "流水线制品文件的 SHA-256 与构建记录不一致");
        }
        List<UploadTarget> targets = instanceIds.stream().map(id -> resolveTarget(id, fileName)).distinct().toList();
        return store(targets, source, fileName, artifactName, checksum, size);
    }

    private ReleaseArtifactUploadResponse store(List<UploadTarget> targets, InputSource file, String fileName,
                                                String artifactName, String checksum, long size) {
        String artifactPath = targets.get(0).remoteTargetPath();
        if (targets.stream().anyMatch(target -> !artifactPath.equals(target.remoteTargetPath()))) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标实例需要使用同一个制品路径");
        }
        for (UploadTarget target : targets) uploadToHost(target, file);

        ReleaseArtifact artifact = new ReleaseArtifact();
        artifact.setArtifactType(isComposeManifest(fileName) ? "DOCKER_COMPOSE" : "JAR");
        artifact.setArtifactName(StringUtils.hasText(artifactName) ? artifactName.trim() : fileName);
        artifact.setArtifactUri(artifactPath);
        artifact.setChecksum(checksum);
        artifact.setSizeBytes(size);
        artifact.setRepositoryType("LOCAL_FILE");
        try {
            releaseArtifactMapper.insert(artifact);
        } catch (DuplicateKeyException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "制品登记失败：数据库序列可能落后于 release_artifact.id，请先检查序列健康状态");
        }

        ReleaseArtifactUploadResponse response = new ReleaseArtifactUploadResponse();
        response.setArtifactId(artifact.getId());
        response.setArtifactName(artifact.getArtifactName());
        response.setArtifactUri(artifact.getArtifactUri());
        response.setChecksum(artifact.getChecksum());
        response.setSizeBytes(artifact.getSizeBytes());
        response.setRepositoryType(artifact.getRepositoryType());
        return response;
    }

    private void uploadToHost(UploadTarget target, InputSource file) {
        try (InputStream input = file.open()) {
            sshFileTransferService.upload(new SshFileUploadRequest(
                    target.host(),
                    target.port(),
                    target.username(),
                    target.authType(),
                    target.password(),
                    target.privateKeyPath(),
                    target.passphrase(),
                    target.remoteTargetPath(),
                    target.remoteTempPath(),
                    input
            ));
        } catch (IOException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "读取上传文件失败: " + exception.getMessage());
        }

    }

    private String validateFile(MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "上传文件不能为空");
        }
        if (file.getSize() > MAX_FILE_SIZE_BYTES) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "上传文件不能超过 200MB");
        }
        String fileName = file.getOriginalFilename();
        if (!StringUtils.hasText(fileName)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "文件名不能为空");
        }
        if (fileName.contains("/") || fileName.contains("\\") || fileName.contains("..") || !fileName.matches("[A-Za-z0-9._-]+")) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "文件名只能包含字母、数字、点、下划线和短横线");
        }
        if (!fileName.toLowerCase(java.util.Locale.ROOT).endsWith(".jar") && !isComposeManifest(fileName)) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "只支持上传 .jar 或 .compose.yml 制品");
        }
        return fileName;
    }

    static boolean isComposeManifest(String path) {
        return path != null && path.toLowerCase(java.util.Locale.ROOT).endsWith(".compose.yml");
    }

    private String checksum(InputSource file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream inputStream = file.open();
                 DigestInputStream digestInputStream = new DigestInputStream(inputStream, digest)) {
                digestInputStream.transferTo(OutputStreamDiscard.INSTANCE);
            }
            return "sha256:" + HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException | IOException exception) {
            throw new BizException(ErrorCode.INTERNAL_ERROR, "计算制品校验和失败: " + exception.getMessage());
        }
    }

    private UploadTarget resolveTarget(Long instanceId, String fileName) {
        RuntimeServiceInstance instance = runtimeServiceInstanceService.getById(instanceId);
        if (instance == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务实例不存在");
        }
        if (instance.getHostId() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务实例未绑定主机");
        }
        BaseServiceEntity service = baseServiceEntityService.getById(instance.getServiceId());
        if (service == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务实例关联的服务不存在");
        }
        Map<String, String> deployConfig = deployConfigService.loadDeployConfig(service.getAppId(), instance.getEnvId());
        if (!hasDeployRoot(deployConfig) && !"infrahub-web".equals(service.getServiceCode())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "服务未配置部署参数");
        }
        DeployRuntimeConfig runtimeConfig = DeployRuntimeConfig.from(deployConfig);
        AssetHost host = assetHostService.getById(instance.getHostId());
        if (host == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机不存在");
        }
        AssetHostSshConfig sshConfig = assetHostSshConfigService.getEnabledByHostId(instance.getHostId());
        if (sshConfig == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标主机缺少启用中的 SSH 配置");
        }
        String root = trimTrailingSlash(runtimeConfig.root());
        String remoteTargetPath = root + "/incoming/" + fileName;
        String remoteTempPath = root + "/incoming/." + fileName + ".tmp." + TEMP_SUFFIX_FORMATTER.format(OffsetDateTime.now());
        return new UploadTarget(
                StringUtils.hasText(host.getIp()) ? host.getIp() : host.getHostname(),
                sshConfig.getSshPort(),
                sshConfig.getUsername(),
                sshConfig.getAuthType(),
                secretValueResolver.resolve(sshConfig.getPasswordSecretRefId()),
                secretValueResolver.resolve(sshConfig.getPrivateKeySecretRefId()),
                secretValueResolver.resolve(sshConfig.getPassphraseSecretRefId()),
                remoteTargetPath,
                remoteTempPath
        );
    }

    private static boolean hasDeployRoot(Map<String, String> deployConfig) {
        return deployConfig != null && StringUtils.hasText(deployConfig.get("deploy.root"));
    }

    private static String trimTrailingSlash(String path) {
        if (path.endsWith("/")) {
            return path.substring(0, path.length() - 1);
        }
        return path;
    }

    private record UploadTarget(
            String host,
            Integer port,
            String username,
            String authType,
            String password,
            String privateKeyPath,
            String passphrase,
            String remoteTargetPath,
            String remoteTempPath
    ) {
    }

    @FunctionalInterface
    private interface InputSource {
        InputStream open() throws IOException;
    }

    private static final class OutputStreamDiscard extends java.io.OutputStream {
        private static final OutputStreamDiscard INSTANCE = new OutputStreamDiscard();

        @Override
        public void write(int b) {
            // discard
        }
    }
}
