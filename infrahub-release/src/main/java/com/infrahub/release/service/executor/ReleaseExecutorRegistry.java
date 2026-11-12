package com.infrahub.release.service.executor;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@RequiredArgsConstructor
public class ReleaseExecutorRegistry {

    private final List<ReleaseExecutor> executors;
    private final Map<String, ReleaseExecutor> executorMap = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        for (ReleaseExecutor executor : executors) {
            String name = executor.getName();
            if (executorMap.containsKey(name)) {
                log.warn("Duplicate executor name detected: {}, later one will override", name);
            }
            executorMap.put(name, executor);
            log.info("Registered release executor: {}", name);
        }
    }

    public ReleaseExecutor getExecutor(String executorType) {
        if (executorType == null || executorType.isBlank()) {
            executorType = "SSH";
        }
        ReleaseExecutor executor = executorMap.get(executorType.toUpperCase());
        if (executor == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "不支持的执行器类型: " + executorType);
        }
        return executor;
    }

    public ReleaseExecutor getRequiredExecutor(String executorType) {
        ReleaseExecutor executor = getExecutor(executorType);
        if (executor == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR,
                    "执行器类型不存在: " + executorType);
        }
        return executor;
    }

    public boolean hasExecutor(String executorType) {
        return executorMap.containsKey(executorType != null ? executorType.toUpperCase() : "SSH");
    }

    public java.util.Set<String> getRegisteredTypes() {
        return executorMap.keySet();
    }
}