package com.infrahub.release.service;

import com.infrahub.infra.ssh.SshExecutionRequest;
import com.infrahub.infra.ssh.SshExecutionResult;
import com.infrahub.infra.ssh.SshExecutorService;
import com.infrahub.release.entity.AssetHost;
import com.infrahub.release.entity.AssetHostSshConfig;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

@Service
@RequiredArgsConstructor
public class ReleasePrecheckService {

    private final SecretValueResolver secretValueResolver;
    private final SshExecutorService sshExecutorService;

    public ReleasePrecheckResult check(
            AssetHost host,
            AssetHostSshConfig sshConfig,
            DeployRuntimeConfig runtimeConfig,
            String artifactUri
    ) {
        return check(host, sshConfig, runtimeConfig, artifactUri, null);
    }

    public ReleasePrecheckResult check(AssetHost host, AssetHostSshConfig sshConfig, DeployRuntimeConfig runtimeConfig,
                                       String artifactUri, String checksum) {
        String command = buildCommand(runtimeConfig, artifactUri);
        if (checksum != null && checksum.matches("sha256:[a-fA-F0-9]{64}")) {
            command = "if ! printf '%%s  %%s\\n' %s %s | sha256sum -c --status; then\n"
                    .formatted(shellQuote(checksum.substring(7).toLowerCase(java.util.Locale.ROOT)), shellQuote(artifactUri))
                    + "  echo '制品 SHA-256 校验失败' >&2\n  exit 1\nfi\n" + command;
        }
        SshExecutionResult result = sshExecutorService.execute(
                SshExecutionRequest.builder()
                        .host(StringUtils.hasText(host.getIp()) ? host.getIp() : host.getHostname())
                        .port(sshConfig.getSshPort())
                        .username(sshConfig.getUsername())
                        .authType(sshConfig.getAuthType())
                        .password(secretValueResolver.resolve(sshConfig.getPasswordSecretRefId()))
                        .privateKeyPath(secretValueResolver.resolve(sshConfig.getPrivateKeySecretRefId()))
                        .passphrase(secretValueResolver.resolve(sshConfig.getPassphraseSecretRefId()))
                        .command(command)
                        .build()
        );

        boolean success = result.isSuccess() && "true".equals(extractMarker(result.getStdout(), "__INFRAHUB_PRECHECK_SUCCESS__"));
        String errorMessage = success ? "" : firstFailureLine(result);
        return new ReleasePrecheckResult(success, result.getStdout(), result.getStderr(), errorMessage, result);
    }

    private static String buildCommand(DeployRuntimeConfig config, String artifactUri) {
        if (ReleaseArtifactUploadService.isComposeManifest(artifactUri)) {
            return buildComposeCommand(config, artifactUri);
        }
        return """
                ROOT=%s
                PID_FILE=%s
                ARTIFACT=%s
                PORT=%s
                SUCCESS=true
                echo "Running precheck"
                if ! JAVA_VERSION_OUTPUT="$(java -version 2>&1 | head -n 1)"; then
                  echo "Java command not found"
                  SUCCESS=false
                else
                  echo "Java version: $JAVA_VERSION_OUTPUT"
                  JAVA_MAJOR="$(printf '%%s\\n' "$JAVA_VERSION_OUTPUT" | sed -E 's/.*version "([0-9]+).*/\\1/')"
                  if ! printf '%%s' "$JAVA_MAJOR" | grep -Eq '^[0-9]+$'; then
                    echo "Java version parse failed: $JAVA_VERSION_OUTPUT"
                    SUCCESS=false
                  elif [ "$JAVA_MAJOR" -lt 17 ]; then
                    echo "JAVA_VERSION_TOO_LOW: $JAVA_MAJOR"
                    SUCCESS=false
                  fi
                fi
                if ! curl --version >/dev/null 2>&1; then
                  echo "curl command not found"
                  SUCCESS=false
                else
                  echo "curl command found"
                fi
                if [ ! -f "$ARTIFACT" ]; then
                  echo "Artifact file not found: $ARTIFACT"
                  SUCCESS=false
                else
                  echo "Artifact file exists: $ARTIFACT"
                fi
                if ! mkdir -p "$ROOT" 2>/dev/null; then
                  echo "Deploy root cannot be created: $ROOT"
                  SUCCESS=false
                elif [ ! -w "$ROOT" ]; then
                  echo "Deploy root is not writable: $ROOT"
                  SUCCESS=false
                else
                  echo "Deploy root writable: $ROOT"
                fi
                PID=""
                if [ -f "$PID_FILE" ]; then PID="$(cat "$PID_FILE" 2>/dev/null || true)"; fi
                PORT_PID="$(ss -ltnp 2>/dev/null | grep ":${PORT} " | sed -n 's/.*pid=\\([0-9][0-9]*\\).*/\\1/p' | head -n 1)"
                if [ -n "$PORT_PID" ]; then
                  if [ -n "$PID" ] && [ "$PORT_PID" = "$PID" ]; then
                    echo "Port $PORT is occupied by current pid file process: $PORT_PID"
                  else
                    echo "Port $PORT is occupied by unknown process: $PORT_PID"
                    SUCCESS=false
                  fi
                else
                  echo "Port $PORT is available"
                fi
                if [ "$SUCCESS" = "true" ]; then
                  echo "前置检查通过"
                else
                  echo "前置检查失败"
                fi
                echo "__INFRAHUB_PRECHECK_SUCCESS__=$SUCCESS"
                exit 0
                """.formatted(
                shellQuote(config.root()),
                shellQuote(config.pidFile()),
                shellQuote(artifactUri),
                shellQuote(String.valueOf(config.port()))
        );
    }

    private static String buildComposeCommand(DeployRuntimeConfig config, String artifactUri) {
        return """
                ROOT=%s
                ARTIFACT=%s
                SUCCESS=true
                if ! docker compose version >/dev/null 2>&1 || ! docker info --format '{{.ServerVersion}}' >/dev/null 2>&1; then
                  echo "Docker Engine or Compose not found or inaccessible"
                  SUCCESS=false
                fi
                if ! curl --version >/dev/null 2>&1; then
                  echo "curl command not found"
                  SUCCESS=false
                fi
                if [ ! -s "$ARTIFACT" ] || [ ! -r "$ARTIFACT" ]; then
                  echo "Compose manifest not found or unreadable: $ARTIFACT"
                  SUCCESS=false
                fi
                if [ ! -d "$ROOT" ] || [ ! -w "$ROOT" ]; then
                  echo "Deploy root not writable: $ROOT"
                  SUCCESS=false
                fi
                echo "__INFRAHUB_PRECHECK_SUCCESS__=$SUCCESS"
                """.formatted(shellQuote(config.root()), shellQuote(artifactUri));
    }

    private static String extractMarker(String stdout, String marker) {
        String prefix = marker + "=";
        for (String line : defaultString(stdout).split("\\R")) {
            if (line.startsWith(prefix)) {
                return line.substring(prefix.length()).trim();
            }
        }
        return "";
    }

    private static String firstFailureLine(SshExecutionResult result) {
        if (!result.isSuccess() && StringUtils.hasText(result.getStderr())) {
            return result.getStderr().trim();
        }
        for (String line : defaultString(result.getStdout()).split("\\R")) {
            if (line.contains("not found")
                    || line.contains("not writable")
                    || line.contains("cannot be created")
                    || line.contains("occupied by unknown")
                    || line.contains("JAVA_VERSION_TOO_LOW")
                    || line.contains("parse failed")) {
                return line.trim();
            }
        }
        if (StringUtils.hasText(result.getStderr())) {
            return result.getStderr().trim();
        }
        return "前置检查执行失败，请检查部署环境配置";
    }

    private static String shellQuote(String value) {
        return "'" + defaultString(value).replace("'", "'\"'\"'") + "'";
    }

    private static String defaultString(String value) {
        return value == null ? "" : value;
    }
}
