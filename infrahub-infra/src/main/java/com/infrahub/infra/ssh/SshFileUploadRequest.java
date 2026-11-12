package com.infrahub.infra.ssh;

import java.io.InputStream;

public record SshFileUploadRequest(
        String host,
        Integer port,
        String username,
        String authType,
        String password,
        String privateKeyPath,
        String passphrase,
        String remoteTargetPath,
        String remoteTempPath,
        InputStream inputStream
) {
}
