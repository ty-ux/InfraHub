package com.infrahub.pipeline.executor;

import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;

public interface PipelineStepExecutor {

    String executorType();

    default void validateRun(PipelineRun run, PipelineStep step) {}

    /**
     * 执行一个流水线步骤。
     *
     * <p>{@code stepRun} 是本次尝试在 {@code pipeline_step_run} 中已经落库的那一行（调用前由
     * 流水线运行服务写入 RUNNING）。它必须在调用执行器之前就存在：需要以“运行 + 步骤尝试”为单位
     * 取得一次性执行凭证时，凭证要绑定到这一次尝试本身，而不是绑定到会被同一运行复用多次的步骤定义。
     */
    PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun);
}
