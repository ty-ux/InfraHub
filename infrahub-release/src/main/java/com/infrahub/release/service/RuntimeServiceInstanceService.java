package com.infrahub.release.service;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.mapper.RuntimeServiceInstanceMapper;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.List;

@Service
public class RuntimeServiceInstanceService extends ServiceImpl<RuntimeServiceInstanceMapper, RuntimeServiceInstance> {

    public List<RuntimeServiceInstance> listByServiceAndEnv(Long serviceId, Long envId) {
        return lambdaQuery()
                .eq(RuntimeServiceInstance::getServiceId, serviceId)
                .eq(RuntimeServiceInstance::getEnvId, envId)
                .list();
    }

    public void updateVersion(List<Long> instanceIds, Long versionId) {
        // MyBatis-Plus 的 in(空集合) 会退化成无条件的全表更新；没有成功实例时必须直接返回
        if (instanceIds == null || instanceIds.isEmpty()) {
            return;
        }
        lambdaUpdate()
                .in(RuntimeServiceInstance::getId, instanceIds)
                .set(RuntimeServiceInstance::getVersionId, versionId)
                .set(RuntimeServiceInstance::getUpdatedAt, OffsetDateTime.now())
                .update();
    }
}
