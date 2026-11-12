package com.infrahub.infra.ssh;

import com.infrahub.common.error.ErrorCode;
import com.infrahub.common.exception.BizException;
import com.jcraft.jsch.ChannelSftp;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import com.jcraft.jsch.SftpException;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.io.InputStream;

@Service
public class JschSshFileTransferService implements SshFileTransferService {

    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final int UPLOAD_TIMEOUT_MILLIS = 60_000;

    @Override
    public SshFileUploadResult upload(SshFileUploadRequest request) {
        validate(request);
        Session session = null;
        ChannelSftp channel = null;
        try (InputStream inputStream = request.inputStream()) {
            session = createSession(request);
            session.setTimeout(UPLOAD_TIMEOUT_MILLIS);
            session.connect(CONNECT_TIMEOUT_MILLIS);

            channel = (ChannelSftp) session.openChannel("sftp");
            channel.connect(CONNECT_TIMEOUT_MILLIS);
            ensureWritableDirectory(channel, parentPath(request.remoteTargetPath()));
            ensureTargetDoesNotExist(channel, request.remoteTargetPath());
            channel.put(inputStream, request.remoteTempPath());
            ensureTargetDoesNotExist(channel, request.remoteTargetPath());
            channel.rename(request.remoteTempPath(), request.remoteTargetPath());
            return SshFileUploadResult.success(request.remoteTargetPath());
        } catch (BizException exception) {
            cleanupTemp(channel, request.remoteTempPath());
            throw exception;
        } catch (JSchException | SftpException | IOException exception) {
            cleanupTemp(channel, request.remoteTempPath());
            throw new BizException(ErrorCode.INTERNAL_ERROR, "制品上传失败: " + exception.getMessage());
        } finally {
            if (channel != null) {
                channel.disconnect();
            }
            if (session != null) {
                session.disconnect();
            }
        }
    }

    private static void validate(SshFileUploadRequest request) {
        if (request.inputStream() == null) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "上传内容不能为空");
        }
        if (!StringUtils.hasText(request.remoteTargetPath()) || !StringUtils.hasText(request.remoteTempPath())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "远端上传路径不能为空");
        }
        if ("PASSWORD".equalsIgnoreCase(request.authType()) && !StringUtils.hasText(request.password())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "SSH 密码不能为空");
        }
        if ("PRIVATE_KEY".equalsIgnoreCase(request.authType()) && !StringUtils.hasText(request.privateKeyPath())) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "SSH 私钥路径不能为空");
        }
    }

    private static Session createSession(SshFileUploadRequest request) throws JSchException {
        JSch jsch = new JSch();
        if ("PRIVATE_KEY".equalsIgnoreCase(request.authType())) {
            if (StringUtils.hasText(request.passphrase())) {
                jsch.addIdentity(request.privateKeyPath(), request.passphrase());
            } else {
                jsch.addIdentity(request.privateKeyPath());
            }
        }
        Session session = jsch.getSession(request.username(), request.host(), request.port() == null ? 22 : request.port());
        if ("PASSWORD".equalsIgnoreCase(request.authType())) {
            session.setPassword(request.password());
        }
        session.setConfig("StrictHostKeyChecking", "no");
        return session;
    }

    private static void ensureWritableDirectory(ChannelSftp channel, String directory) throws SftpException {
        String[] parts = directory.split("/");
        String path = directory.startsWith("/") ? "/" : "";
        for (String part : parts) {
            if (!StringUtils.hasText(part)) {
                continue;
            }
            path = "/".equals(path) ? path + part : path + "/" + part;
            try {
                channel.stat(path);
            } catch (SftpException ignored) {
                channel.mkdir(path);
            }
        }
        try {
            channel.put(new java.io.ByteArrayInputStream(new byte[0]), directory + "/.infrahub-upload-write-test");
            channel.rm(directory + "/.infrahub-upload-write-test");
        } catch (SftpException exception) {
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标目录不可写: " + directory);
        }
    }

    private static void ensureTargetDoesNotExist(ChannelSftp channel, String remoteTargetPath) throws SftpException {
        try {
            channel.stat(remoteTargetPath);
            throw new BizException(ErrorCode.VALIDATION_ERROR, "目标文件已存在: " + remoteTargetPath);
        } catch (SftpException exception) {
            if (exception.id != ChannelSftp.SSH_FX_NO_SUCH_FILE) {
                throw exception;
            }
        }
    }

    private static void cleanupTemp(ChannelSftp channel, String remoteTempPath) {
        if (channel == null || !StringUtils.hasText(remoteTempPath)) {
            return;
        }
        try {
            channel.rm(remoteTempPath);
        } catch (Exception ignored) {
            // Best-effort cleanup only.
        }
    }

    private static String parentPath(String path) {
        int index = path.lastIndexOf('/');
        if (index <= 0) {
            return ".";
        }
        return path.substring(0, index);
    }
}
