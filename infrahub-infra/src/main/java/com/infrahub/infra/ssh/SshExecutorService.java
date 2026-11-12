package com.infrahub.infra.ssh;

public interface SshExecutorService {

    SshExecutionResult execute(SshExecutionRequest request);
}
