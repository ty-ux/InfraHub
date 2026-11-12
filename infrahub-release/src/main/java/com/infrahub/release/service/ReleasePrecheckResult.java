package com.infrahub.release.service;

import com.infrahub.infra.ssh.SshExecutionResult;

public record ReleasePrecheckResult(
        boolean success,
        String stdout,
        String stderr,
        String errorMessage,
        SshExecutionResult executionResult
) {

    public boolean isSuccess() {
        return success;
    }

    public String fullLog() {
        return "precheck stdout:\n" + (stdout == null ? "" : stdout)
                + "\n\nprecheck stderr:\n" + (stderr == null ? "" : stderr);
    }
}
