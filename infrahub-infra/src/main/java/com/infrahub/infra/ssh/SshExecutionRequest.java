package com.infrahub.infra.ssh;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SshExecutionRequest {

    String host;
    Integer port;
    String username;
    String authType;
    String password;
    String privateKeyPath;
    String passphrase;
    String command;

    /**
     * 命令执行超时（毫秒）。{@code null} 或非正数时用执行器的默认值，
     * 因此既有发布调用方完全不传本字段即可保持原有行为。
     *
     * <p>采集侧必须传：只有真正把超时下推到执行器，超时才能变成
     * "杀掉底层进程 / 断开 JSch 会话"的可回收动作，而不是调用方单方面不再等待
     * ——后者会让进程与线程在后台继续堆积。
     */
    Long timeoutMillis;
}
