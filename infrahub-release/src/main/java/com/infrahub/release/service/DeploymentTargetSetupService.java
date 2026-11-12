package com.infrahub.release.service;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.entity.ConfigSecretRef;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.mapper.BaseServiceMapper;
import com.infrahub.base.mapper.ConfigSecretRefMapper;
import com.infrahub.common.context.UserContextHolder;
import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.release.dto.HostSshSetupRequest;
import com.infrahub.release.dto.RegisterRuntimeInstanceRequest;
import com.infrahub.release.entity.*;
import com.infrahub.release.mapper.*;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;

/** Administrative setup only: never executes SSH or claims an instance is running. */
@Service
@RequiredArgsConstructor
public class DeploymentTargetSetupService {
    private final AssetHostMapper hostMapper;
    private final AssetHostSshConfigMapper sshMapper;
    private final ConfigSecretRefMapper secretMapper;
    private final BaseServiceMapper serviceMapper;
    private final BaseEnvMapper envMapper;
    private final RuntimeServiceInstanceMapper instanceMapper;
    private final ReleaseOrderMapper releaseMapper;
    private final ReleaseRollbackOrderMapper rollbackMapper;
    private final RuntimeExecutionUnknownService unknownService;
    private final SysAuditLogMapper auditMapper;

    public AssetHostSshConfig getSsh(Long hostId) {
        if (hostMapper.selectById(hostId) == null) throw invalid("主机不存在");
        return singleSsh(hostId);
    }

    @Transactional
    public AssetHostSshConfig saveSsh(Long hostId, HostSshSetupRequest request) {
        requireTransaction();
        if (hostMapper.selectFullForSetup(hostId) == null) throw invalid("主机不存在");
        ConfigSecretRef secret = secretMapper.selectById(request.privateKeySecretRefId());
        if (secret == null || !"LOCAL_FILE".equals(secret.getSecretProvider())) {
            throw invalid("请选择 LOCAL_FILE 类型的私钥路径引用，不能填写密码或私钥内容");
        }
        if (Boolean.TRUE.equals(request.enabled())) requirePrivateKeyFile(secret.getSecretPath());
        AssetHostSshConfig config = singleSsh(hostId);
        OffsetDateTime now = OffsetDateTime.now();
        if (config == null) {
            config = new AssetHostSshConfig();
            config.setHostId(hostId);
            config.setCreatedAt(now);
        }
        config.setUsername(request.username());
        config.setSshPort(request.sshPort());
        config.setAuthType("PRIVATE_KEY");
        config.setPrivateKeySecretRefId(request.privateKeySecretRefId());
        config.setPasswordSecretRefId(null);
        config.setPassphraseSecretRefId(null);
        config.setEnabled(request.enabled());
        config.setUpdatedAt(now);
        if (config.getId() == null) {
            if (sshMapper.insert(config) != 1) throw new IllegalStateException("SSH setup insert failed");
        } else {
            // Explicit nulls prevent a previous password/passphrase reference surviving an auth change.
            int changed = sshMapper.update(null, new UpdateWrapper<AssetHostSshConfig>()
                    .eq("id", config.getId()).set("username", config.getUsername())
                    .set("ssh_port", config.getSshPort()).set("auth_type", "PRIVATE_KEY")
                    .set("private_key_secret_ref_id", config.getPrivateKeySecretRefId())
                    .set("password_secret_ref_id", null).set("passphrase_secret_ref_id", null)
                    .set("enabled", config.getEnabled()).set("updated_at", now));
            if (changed != 1) throw new IllegalStateException("SSH setup update failed");
        }
        auditMapper.insertAuditLog(UserContextHolder.getUserIdOrDefault(1L), "HOST", hostId,
                "CONFIGURE_SSH", null, "{\"authType\":\"PRIVATE_KEY\",\"credentialType\":\"LOCAL_FILE\"}", "SUCCESS", null);
        return config;
    }

    @Transactional
    public RuntimeServiceInstance registerInstance(RegisterRuntimeInstanceRequest request) {
        requireTransaction();
        // Same service lock as release claims, rollbacks and deregistration.
        if (serviceMapper.lockById(request.serviceId()) == null) throw invalid("服务不存在");
        BaseServiceEntity service = serviceMapper.selectById(request.serviceId());
        if (service == null || Boolean.TRUE.equals(service.getIsDeleted()) || !"ENABLED".equals(service.getStatus())) {
            throw invalid("服务不存在或未启用");
        }
        BaseEnv env = envMapper.selectById(request.envId());
        if (env == null || Boolean.TRUE.equals(env.getIsDeleted()) || !"ENABLED".equals(env.getStatus())) {
            throw invalid("环境不存在或未启用");
        }
        AssetHost host = hostMapper.selectFullForSetup(request.hostId());
        if (host == null || !Objects.equals(host.getEnvId(), request.envId())) {
            throw invalid("请选择属于当前环境的主机");
        }
        unknownService.assertNoUnresolvedUnknown(request.serviceId(), request.envId());
        long releases = releaseMapper.selectCount(new QueryWrapper<ReleaseOrder>()
                .eq("service_id", request.serviceId()).eq("env_id", request.envId()).eq("status", "RUNNING"));
        if (releases > 0 || rollbackMapper.countRunningByServiceAndEnv(request.serviceId(), request.envId()) > 0) {
            throw invalid("当前服务和环境存在执行中的发布或回滚，暂不能登记实例");
        }
        String name = request.instanceName().trim();
        if (name.isEmpty() || name.length() > 128) throw invalid("实例名称须为 1 至 128 个字符");
        if (instanceMapper.selectCount(new QueryWrapper<RuntimeServiceInstance>()
                .eq("env_id", request.envId()).eq("instance_name", name)) > 0) {
            throw invalid("当前环境已有同名实例");
        }
        if (instanceMapper.selectCount(new QueryWrapper<RuntimeServiceInstance>()
                .eq("service_id", request.serviceId()).eq("env_id", request.envId()).eq("host_id", request.hostId())) > 0) {
            throw invalid("该服务在当前主机已有实例登记");
        }
        RuntimeServiceInstance instance = new RuntimeServiceInstance();
        instance.setServiceId(request.serviceId());
        instance.setEnvId(request.envId());
        instance.setHostId(request.hostId());
        instance.setInstanceName(name);
        instance.setInstanceType("SPRING_BOOT");
        instance.setInstanceStatus("UNKNOWN");
        instance.setHealthStatus("UNKNOWN");
        instance.setCreatedAt(OffsetDateTime.now());
        instance.setUpdatedAt(instance.getCreatedAt());
        // Version, start time and heartbeat remain unset until there is real evidence.
        try {
            if (instanceMapper.insert(instance) != 1) throw new IllegalStateException("Instance insert failed");
        } catch (DuplicateKeyException exception) {
            throw invalid("当前环境已有同名实例，请刷新后重试");
        }
        auditMapper.insertAuditLog(UserContextHolder.getUserIdOrDefault(1L), "SERVICE_INSTANCE", instance.getId(),
                "REGISTER", null, "{\"instanceStatus\":\"UNKNOWN\",\"healthStatus\":\"UNKNOWN\"}", "SUCCESS", null);
        return instance;
    }

    private AssetHostSshConfig singleSsh(Long hostId) {
        List<AssetHostSshConfig> configs = sshMapper.selectList(new QueryWrapper<AssetHostSshConfig>().eq("host_id", hostId));
        if (configs.size() > 1) throw invalid("该主机存在多条 SSH 配置，请先整理配置，避免连接歧义");
        return configs.isEmpty() ? null : configs.get(0);
    }

    private static void requirePrivateKeyFile(String value) {
        try {
            if (value == null || value.isBlank()) throw invalid("私钥路径不能为空");
            Path path = Path.of(value);
            if (!path.isAbsolute() || !Files.isRegularFile(path) || !Files.isReadable(path)
                    || path.getFileName().toString().endsWith(".pub")) {
                throw invalid("私钥路径必须指向后端机器上可读取的私钥文件，不能选择 .pub 公钥");
            }
        } catch (InvalidPathException exception) {
            throw invalid("私钥路径格式无效");
        }
    }

    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("Deployment target setup requires an active transaction");
        }
    }

    private static BizException invalid(String message) {
        return new BizException(ErrorCode.VALIDATION_ERROR, message);
    }
}
