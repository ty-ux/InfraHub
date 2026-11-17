package com.infrahub.release.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.entity.PipelineStepRun;
import com.infrahub.pipeline.executor.PipelineStepExecutionResult;
import com.infrahub.pipeline.executor.PipelineStepExecutor;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.release.service.SecretValueResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.error.YAMLException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Component
@RequiredArgsConstructor
public class ComposeTemplateStepExecutor implements PipelineStepExecutor {
    private static final Pattern PUSH_DIGEST = Pattern.compile("(?m)\\bdigest: (sha256:[a-f0-9]{64}) size: [0-9]+[ \\r]*$");
    private static final String MANIFEST_NAME = "application.compose.yml";

    private final ObjectMapper objectMapper;
    private final PipelineStepLogStore logs;
    private final SecretValueResolver secrets;
    private final ComposePipelineTemplateService templates;

    @Override
    public String executorType() {
        return ComposePipelineTemplateService.EXECUTOR;
    }

    @Override
    public void validateRun(PipelineRun run, PipelineStep step) {
        var task = readTask(step);
        var spec = task.spec();
        if (!spec.appId().equals(run.getAppId()) || !spec.serviceId().equals(run.getServiceId()) || !spec.envId().equals(run.getEnvId())) {
            throw ComposePipelineTemplateService.invalid("请选择创建模板时配置的应用、服务和环境");
        }
        if (task.operation() == ComposePipelineTemplateService.Operation.CHECKOUT) templates.validateTargets(spec);
    }

    private ComposePipelineTemplateService.Task readTask(PipelineStep step) {
        try {
            return objectMapper.readValue(step.getScriptContent(), ComposePipelineTemplateService.Task.class);
        } catch (IOException | IllegalArgumentException error) {
            throw ComposePipelineTemplateService.invalid("无法读取 Compose 模板参数");
        }
    }

    @Override
    public PipelineStepExecutionResult execute(PipelineRun run, PipelineStep step, PipelineStepRun stepRun) {
        OffsetDateTime started = OffsetDateTime.now();
        Path log = null;
        try {
            log = logs.create(stepRun);
            var task = readTask(step);
            var spec = task.spec();
            Path descriptor = logs.artifactDescriptor(run.getId());
            Path work = descriptor.getParent();
            var commands = new Commands(log, step.getTimeoutSeconds() == null ? 300 : step.getTimeoutSeconds());
            switch (task.operation()) {
                case PREFLIGHT -> preflight(spec, commands, work);
                case CHECKOUT -> checkout(spec, commands, work);
                case BUILD_BACKEND -> build(spec, spec.backend(), commands, source(work), run.getId());
                case BUILD_FRONTEND -> build(spec, spec.frontend(), commands, source(work), run.getId());
                case PUSH -> push(spec, commands, work, run.getId());
                case MANIFEST -> manifest(spec, commands, work, descriptor);
            }
            return PipelineStepExecutionResult.success(PipelineStepLogStore.preview(log, 4000), started, OffsetDateTime.now());
        } catch (StepFailure failure) {
            return failure(log, failure.getMessage(), started);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return failure(log, "Compose 模板步骤已中断", started);
        } catch (IOException failure) {
            return failure(log, "无法读取工作目录或启动命令，请检查构建节点和步骤日志", started);
        }
    }

    private static void preflight(ComposePipelineSpec spec, Commands commands, Path work) throws IOException, InterruptedException, StepFailure {
        commands.run(work, List.of("git", "--version"));
        commands.run(work, docker(spec, "version", "--format", "{{.Server.Version}}"));
        commands.run(work, docker(spec, "compose", "version"));
    }

    private void checkout(ComposePipelineSpec spec, Commands commands, Path work) throws IOException, InterruptedException, StepFailure {
        Path checkout = Files.createTempDirectory(work, "source-");
        String key = secrets.resolve(spec.gitSshSecretRefId());
        Map<String, String> environment = new HashMap<>(Map.of("GIT_TERMINAL_PROMPT", "0", "GIT_ALLOW_PROTOCOL", "https:ssh"));
        if (key != null) {
            if (!Files.isRegularFile(Path.of(key))) throw new StepFailure("Git SSH 私钥文件不存在");
            environment.put("GIT_SSH_COMMAND", "ssh -o BatchMode=yes -o IdentitiesOnly=yes -i "
                    + ComposeRemoteCommand.quote(Path.of(key).toAbsolutePath().toString()));
        }
        commands.run(work, List.of("git", "clone", "--depth", "1", "--single-branch", "--branch", spec.branch(), "--",
                spec.repositoryUrl(), checkout.toString()), environment, null);
        Path revision = work.resolve("revision.txt");
        commands.run(checkout, List.of("git", "rev-parse", "HEAD"), Map.of(), revision);
        String commit = Files.readString(revision).strip();
        if (!commit.matches("[a-f0-9]{40}|[a-f0-9]{64}")) throw new StepFailure("无法确认 Git 提交");
        commands.note("Git commit " + commit);
        Files.writeString(work.resolve("checkout.txt"), checkout.getFileName().toString());
    }

    private static Path source(Path work) throws IOException, StepFailure {
        String name = Files.readString(work.resolve("checkout.txt")).strip();
        if (!name.matches("source-[0-9]+")) throw new StepFailure("找不到本次运行的代码目录");
        return repositoryPath(work, name, true);
    }

    private static Path repositoryPath(Path source, String relative, boolean directory) throws IOException, StepFailure {
        Path result = source.resolve(relative).toRealPath();
        if (!result.startsWith(source.toRealPath()) || (directory ? !Files.isDirectory(result) : !Files.isRegularFile(result))) {
            throw new StepFailure("请检查仓库中的文件或目录路径");
        }
        return result;
    }

    private static void build(ComposePipelineSpec spec, ComposePipelineSpec.Image image, Commands commands, Path source, Long runId)
            throws IOException, InterruptedException, StepFailure {
        Path dockerfile = repositoryPath(source, image.dockerfile(), false);
        Path context = repositoryPath(source, image.context(), true);
        commands.run(source, docker(spec, "build", "--file", dockerfile.toString(), "--tag", tag(spec, image, runId), context.toString()));
    }

    private void push(ComposePipelineSpec spec, Commands commands, Path work, Long runId)
            throws IOException, InterruptedException, StepFailure {
        String backend = pushImage(spec, spec.backend(), commands, work, runId);
        String frontend = pushImage(spec, spec.frontend(), commands, work, runId);
        objectMapper.writeValue(work.resolve("images.json").toFile(), Map.of("backend", backend, "frontend", frontend));
    }

    private static String pushImage(ComposePipelineSpec spec, ComposePipelineSpec.Image image, Commands commands, Path work, Long runId)
            throws IOException, InterruptedException, StepFailure {
        long start = Files.size(commands.log());
        commands.run(work, docker(spec, "push", tag(spec, image, runId)));
        String tail = PipelineStepLogStore.tail(commands.log(), (int) Math.min(Files.size(commands.log()) - start, 32_768));
        var match = PUSH_DIGEST.matcher(tail);
        if (!match.find()) throw new StepFailure("镜像已推送，但未能从仓库确认镜像摘要");
        return spec.registry() + "/" + image.repository() + "@" + match.group(1);
    }

    private static String tag(ComposePipelineSpec spec, ComposePipelineSpec.Image image, Long runId) {
        return spec.registry() + "/" + image.repository() + ":run-" + runId;
    }

    private static List<String> docker(ComposePipelineSpec spec, String... arguments) {
        var command = new ArrayList<>(List.of("docker"));
        if (spec.dockerContext() != null) command.addAll(List.of("--context", spec.dockerContext()));
        command.addAll(List.of(arguments));
        return command;
    }

    private void manifest(ComposePipelineSpec spec, Commands commands, Path work, Path descriptor)
            throws IOException, InterruptedException, StepFailure {
        Path composeFile = repositoryPath(source(work), spec.composeFile(), false);
        JsonNode images = objectMapper.readTree(work.resolve("images.json").toFile());
        ObjectNode manifest = renderManifest(composeFile, spec, images);
        Path artifact = work.resolve(MANIFEST_NAME);
        objectMapper.writerWithDefaultPrettyPrinter().writeValue(artifact.toFile(), manifest);
        commands.run(work, docker(spec, "compose", "--project-name", spec.pipelineCode(), "-f", artifact.toString(),
                "config", "--no-interpolate", "--no-env-resolution", "--quiet"));
        byte[] bytes = Files.readAllBytes(artifact);
        String checksum;
        try {
            checksum = "sha256:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
        String commit = Files.readString(work.resolve("revision.txt")).strip();
        objectMapper.writeValue(descriptor.toFile(), new RegisterArtifactPipelineStepExecutor.ArtifactDescriptor(
                "DOCKER_COMPOSE", spec.pipelineCode(), null, MANIFEST_NAME, checksum, commit, (long) bytes.length));
        commands.note("Compose 制品已生成，前后端镜像已固定为仓库摘要");
    }

    ObjectNode renderManifest(Path composeFile, ComposePipelineSpec spec, JsonNode images) throws IOException, StepFailure {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        JsonNode value;
        try (var reader = Files.newBufferedReader(composeFile, StandardCharsets.UTF_8)) {
            value = objectMapper.valueToTree(new Yaml(new SafeConstructor(options)).load(reader));
        } catch (YAMLException | IllegalArgumentException invalidYaml) {
            throw new StepFailure("无法解析 Compose 清单");
        }
        if (!(value instanceof ObjectNode document) || document.has("include") || !(document.get("services") instanceof ObjectNode services)) {
            throw new StepFailure("请提供包含 services 的独立 Compose 清单");
        }
        document.remove("name");
        pin(services, spec.backend().composeService(), images.path("backend").asText());
        pin(services, spec.frontend().composeService(), images.path("frontend").asText());
        for (JsonNode service : services) {
            if (service.has("build") || service.has("extends") || service.has("env_file") || service.has("label_file") || service.has("develop")) {
                throw new StepFailure("Compose 清单仅支持构建所选前后端服务，且不能依赖额外的仓库文件");
            }
            for (JsonNode volume : service.path("volumes")) {
                if (volume.isObject() && "bind".equals(volume.path("type").asText())) {
                    requireAbsolute(volume.path("source").asText());
                } else if (volume.isTextual()) {
                    String mount = volume.asText();
                    int separator = mount.indexOf(':');
                    if (separator >= 0) {
                        String from = mount.substring(0, separator);
                        if (from.startsWith(".") || from.startsWith("~") || from.contains("$") || from.contains("\\")) requireAbsolute(from);
                    }
                }
            }
        }
        for (String section : List.of("secrets", "configs")) {
            for (JsonNode entry : document.path(section)) {
                if (entry.has("file")) requireAbsolute(entry.path("file").asText());
            }
        }
        return document;
    }

    private static void pin(ObjectNode services, String name, String image) throws StepFailure {
        if (!(services.get(name) instanceof ObjectNode service)) throw new StepFailure("Compose 清单中找不到服务 " + name);
        if (!image.matches("[a-z0-9./:_-]+@sha256:[a-f0-9]{64}")) throw new StepFailure("镜像摘要无效");
        service.remove(List.of("build", "pull_policy"));
        service.put("image", image);
    }

    private static void requireAbsolute(String path) throws StepFailure {
        if (!path.startsWith("/") || path.contains("$")) throw new StepFailure("Compose 引用的服务器文件必须使用绝对路径");
    }

    private static PipelineStepExecutionResult failure(Path log, String message, OffsetDateTime started) {
        String output = message;
        if (log != null) {
            try {
                Files.writeString(log, "\n" + message + "\n", StandardOpenOption.APPEND);
                output = PipelineStepLogStore.preview(log, 4000);
            } catch (IOException ignored) {
                // Keep the execution error even if log storage is unavailable.
            }
        }
        return PipelineStepExecutionResult.failure(-1, output, message, started, OffsetDateTime.now());
    }

    static final class StepFailure extends Exception {
        StepFailure(String message) { super(message); }
    }

    private record Commands(Path log, long deadline) {
        Commands(Path log, int timeoutSeconds) {
            this(log, System.nanoTime() + TimeUnit.SECONDS.toNanos(timeoutSeconds));
        }

        void note(String message) throws IOException {
            Files.writeString(log, message + "\n", StandardOpenOption.APPEND);
        }

        void run(Path directory, List<String> arguments) throws IOException, InterruptedException, StepFailure {
            run(directory, arguments, Map.of(), null);
        }

        void run(Path directory, List<String> arguments, Map<String, String> environment, Path output)
                throws IOException, InterruptedException, StepFailure {
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new StepFailure("Compose 模板步骤超时");
            String label = String.join(" ", arguments.subList(0, arguments.get(1).equals("--context") ? 4 : 2));
            note("$ " + label);
            ProcessBuilder builder = new ProcessBuilder(arguments).directory(directory.toFile())
                    .redirectError(ProcessBuilder.Redirect.appendTo(log.toFile()))
                    .redirectOutput(output == null ? ProcessBuilder.Redirect.appendTo(log.toFile()) : ProcessBuilder.Redirect.to(output.toFile()));
            builder.environment().putAll(environment);
            Process process = builder.start();
            try {
                process.getOutputStream().close();
                if (!process.waitFor(remaining, TimeUnit.NANOSECONDS)) throw new StepFailure("Compose 模板步骤超时");
                if (process.exitValue() != 0) throw new StepFailure(label + " 执行失败，退出码 " + process.exitValue() + "，请查看步骤日志");
            } finally {
                if (process.isAlive()) {
                    process.descendants().forEach(ProcessHandle::destroyForcibly);
                    process.destroyForcibly();
                }
            }
        }
    }
}
