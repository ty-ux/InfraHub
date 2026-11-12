package com.infrahub.infra.ssh;

public record SshFileUploadResult(
        boolean success,
        String remotePath
) {

    public static SshFileUploadResult success(String remotePath) {
        return new SshFileUploadResult(true, remotePath);
    }
}
