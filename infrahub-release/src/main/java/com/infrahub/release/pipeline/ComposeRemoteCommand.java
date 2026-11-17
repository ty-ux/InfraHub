package com.infrahub.release.pipeline;

import org.springframework.util.StringUtils;

public final class ComposeRemoteCommand {
    private ComposeRemoteCommand() {}

    static String create(ComposePipelineSpec spec) {
        String owner = spec.appId() + ":" + spec.envId() + ":" + spec.serviceId();
        return """
                set -eu
                root=%s
                project=${instanceNameShell}
                owner=%s:"$project"
                case "$project" in
                  ''|[!a-z0-9]*|*[!a-z0-9_-]*) echo 'Invalid Compose project name' >&2; exit 1 ;;
                esac
                artifact=${artifactUriShell}
                checksum=${artifactChecksumShell}
                name=${artifact##*/}
                case "$artifact" in
                  "$root"/incoming/*.compose.yml) ;;
                  *) echo 'Compose artifact must be in the deployment directory' >&2; exit 1 ;;
                esac
                [ "$artifact" = "$root/incoming/$name" ] || exit 1
                actual=$(sha256sum -- "$artifact")
                if [ "sha256:${actual%%%% *}" != "$checksum" ]; then
                  echo 'Compose artifact checksum mismatch' >&2; exit 1
                fi
                cd "$root"
                exec 9>.compose.lock
                flock -n 9 || { echo 'Compose deployment already running' >&2; exit 1; }
                if [ -f .infrahub-compose-owner ]; then
                  [ "$(cat .infrahub-compose-owner)" = "$owner" ] || { echo 'Compose deployment directory is already in use' >&2; exit 1; }
                else
                  resources=$(docker container ls -aq --filter "label=com.docker.compose.project=$project")
                  networks=$(docker network ls -q --filter "label=com.docker.compose.project=$project")
                  volumes=$(docker volume ls -q --filter "label=com.docker.compose.project=$project")
                  [ -z "$resources$networks$volumes" ] || { echo 'Compose project name is already in use on this host' >&2; exit 1; }
                  printf '%%s\\n' "$owner" > .infrahub-compose-owner
                fi
                docker compose --project-name "$project" --project-directory "$root" -f "$artifact" \\
                  up -d --no-build --pull missing --remove-orphans --wait --wait-timeout 80
                %s
                docker compose --project-name "$project" --project-directory "$root" -f "$artifact" ps
                """.formatted(quote(spec.deployRoot()), quote(owner), healthCheck(spec));
    }

    private static String healthCheck(ComposePipelineSpec spec) {
        if (!StringUtils.hasText(spec.healthUrl())) return "";
        int timeout = spec.healthTimeoutSeconds() == null ? 20 : spec.healthTimeoutSeconds();
        return """
                health_url=%s
                deadline=$(($(date +%%s) + %d))
                until curl -fsS --max-time 3 --output /dev/null -- "$health_url"; do
                  [ "$(date +%%s)" -lt "$deadline" ] || { echo 'HTTP health check timed out' >&2; exit 1; }
                  sleep 2
                done
                echo 'HTTP health check passed'
                """.formatted(quote(spec.healthUrl()), timeout).stripTrailing();
    }

    public static String quote(String value) {
        return "'" + (value == null ? "" : value.replace("'", "'\"'\"'")) + "'";
    }
}
