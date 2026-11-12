package com.infrahub.infra.ssh;

public interface SshFileTransferService {

    SshFileUploadResult upload(SshFileUploadRequest request);
}
