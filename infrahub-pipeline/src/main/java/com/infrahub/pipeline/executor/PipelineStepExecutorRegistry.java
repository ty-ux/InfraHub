package com.infrahub.pipeline.executor;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class PipelineStepExecutorRegistry {

    private final Map<String, PipelineStepExecutor> executors;

    public PipelineStepExecutorRegistry(List<PipelineStepExecutor> executors) {
        this.executors = executors.stream()
                .collect(Collectors.toMap(
                        executor -> normalize(executor.executorType()),
                        Function.identity()
                ));
    }

    public PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        String executorType = StringUtils.hasText(step.getExecutorType()) ? step.getExecutorType() : "LOCAL_SHELL";
        PipelineStepExecutor executor = executors.get(normalize(executorType));
        if (executor == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "不支持的流水线执行器: " + executorType);
        }
        return executor.execute(run, step, stepRun);
    }

    public void validateRun(PipelineRun run, List<PipelineStep> steps) {
        for (PipelineStep step : steps) {
            PipelineStepExecutor executor = executors.get(normalize(step.getExecutorType()));
            if (executor != null) executor.validateRun(run, step);
        }
    }

    private static String normalize(String value) {
        return value == null ? "" : value.toUpperCase(Locale.ROOT);
    }
}
