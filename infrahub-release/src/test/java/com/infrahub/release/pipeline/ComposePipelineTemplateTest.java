package com.infrahub.release.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.base.entity.BaseEnv;
import com.infrahub.base.entity.BaseServiceEntity;
import com.infrahub.base.entity.ConfigEnvConfig;
import com.infrahub.base.entity.ConfigGroup;
import com.infrahub.base.mapper.BaseEnvMapper;
import com.infrahub.base.service.BaseEnvService;
import com.infrahub.base.service.BaseServiceEntityService;
import com.infrahub.base.service.ConfigEnvConfigService;
import com.infrahub.base.service.ConfigGroupService;
import com.infrahub.base.service.ConfigSecretRefService;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineDef;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.entity.PipelineStage;
import com.infrahub.pipeline.entity.PipelineStep;
import com.infrahub.pipeline.service.PipelineDefService;
import com.infrahub.pipeline.service.PipelineStageService;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.pipeline.service.PipelineStepService;
import com.infrahub.release.entity.AssetHostSshConfig;
import com.infrahub.release.entity.RuntimeServiceInstance;
import com.infrahub.release.service.AssetHostSshConfigService;
import com.infrahub.release.service.DeployConfigService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import com.infrahub.release.service.SecretValueResolver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ComposePipelineTemplateTest {
    private final ObjectMapper json = new ObjectMapper();
    private final ComposePipelineSpec spec = new ComposePipelineSpec(1L, 2L, 3L, "demo-compose", "Demo",
            "https://git.example.com/team/project.git", "main", null, "builder", "registry.example.com:5000",
            new ComposePipelineSpec.Image("api", "team/api", ".", "backend/Dockerfile"),
            new ComposePipelineSpec.Image("web", "team/web", "frontend", "frontend/Dockerfile"),
            "compose.yml", "/opt/demo", true, "http://127.0.0.1:8088/health", 20);

    @Test
    void createsOrderedStagesAndInstallsTheSameCommandForDeployAndRollback() throws Exception {
        var definitions = mock(PipelineDefService.class);
        var stages = mock(PipelineStageService.class);
        var steps = mock(PipelineStepService.class);
        var envs = mock(BaseEnvService.class);
        var services = mock(BaseServiceEntityService.class);
        var instances = mock(RuntimeServiceInstanceService.class);
        var ssh = mock(AssetHostSshConfigService.class);
        var deployConfig = mock(DeployConfigService.class);
        var groups = mock(ConfigGroupService.class);
        var configs = mock(ConfigEnvConfigService.class);
        when(envs.getById(2L)).thenReturn(new BaseEnv());
        var service = new BaseServiceEntity();
        service.setAppId(1L);
        when(services.getById(3L)).thenReturn(service);
        when(instances.listByServiceAndEnv(3L, 2L)).thenReturn(List.of(instance(4L, "demo-a"), instance(5L, "demo-b")));
        when(ssh.getEnabledByHostId(any())).thenReturn(new AssetHostSshConfig());
        when(deployConfig.loadDeployConfig(1L, 2L)).thenReturn(Map.of());
        when(definitions.save(any())).thenAnswer(call -> {
            call.<PipelineDef>getArgument(0).setId(100L);
            return true;
        });
        AtomicLong ids = new AtomicLong();
        when(stages.save(any())).thenAnswer(call -> {
            call.<PipelineStage>getArgument(0).setId(ids.incrementAndGet());
            return true;
        });
        when(groups.save(any())).thenAnswer(call -> {
            call.<ConfigGroup>getArgument(0).setId(200L);
            return true;
        });
        var template = new ComposePipelineTemplateService(json, definitions, stages, steps, mock(BaseEnvMapper.class), envs,
                services, instances, ssh, mock(ConfigSecretRefService.class), deployConfig, groups, configs);

        assertEquals(100L, template.create(spec));
        var captured = ArgumentCaptor.forClass(PipelineStep.class);
        verify(steps, times(8)).save(captured.capture());
        List<PipelineStep> plan = captured.getAllValues();
        assertEquals(List.of(1L, 2L, 3L, 3L, 4L, 5L, 5L, 6L), plan.stream().map(PipelineStep::getStageId).toList());
        assertEquals("REGISTER_ARTIFACT", plan.get(6).getExecutorType());
        assertEquals("RELEASE_DEPLOY", plan.get(7).getExecutorType());
        var executor = new ComposeTemplateStepExecutor(json, mock(PipelineStepLogStore.class), mock(SecretValueResolver.class), template);
        var run = new PipelineRun();
        run.setAppId(1L);
        run.setServiceId(3L);
        run.setEnvId(2L);
        assertDoesNotThrow(() -> executor.validateRun(run, plan.get(1)));
        run.setEnvId(99L);
        assertThrows(BizException.class, () -> executor.validateRun(run, plan.get(1)));

        var configRows = ArgumentCaptor.forClass(ConfigEnvConfig.class);
        verify(configs, times(3)).save(configRows.capture());
        var command = configRows.getAllValues().stream().filter(row -> row.getConfigKey().equals("deploy.command"))
                .findFirst().orElseThrow().getConfigValue();
        assertTrue(configRows.getAllValues().stream().anyMatch(row -> row.getConfigKey().equals("rollback.command")
                && row.getConfigValue().equals(command)));
        assertTrue(command.contains("project=${instanceNameShell}"));
        assertTrue(command.contains("sha256:${actual%% *}"));
        assertTrue(command.contains("HTTP health check passed"));
    }

    @Test
    void pinsBothBuiltImagesAndRejectsFilesMissingFromTheDeployArtifact(@TempDir Path work) throws Exception {
        var executor = new ComposeTemplateStepExecutor(json, mock(PipelineStepLogStore.class), mock(SecretValueResolver.class),
                mock(ComposePipelineTemplateService.class));
        String api = "registry.example.com:5000/team/api@sha256:" + "1".repeat(64);
        String web = "registry.example.com:5000/team/web@sha256:" + "2".repeat(64);
        var images = json.createObjectNode().put("backend", api).put("frontend", web);
        Path compose = work.resolve("compose.yml");
        String contents = """
                name: build-only
                services:
                  api:
                    build: .
                    environment:
                      DB_PASSWORD: ${DB_PASSWORD:?set it on the server}
                  web:
                    build: frontend
                    pull_policy: build
                """;
        Files.writeString(compose, contents);
        var manifest = executor.renderManifest(compose, spec, images);
        assertFalse(manifest.has("name"));
        assertEquals(api, manifest.at("/services/api/image").asText());
        assertEquals(web, manifest.at("/services/web/image").asText());
        assertFalse(manifest.path("services").path("web").has("pull_policy"));
        assertFalse(manifest.path("services").path("api").has("build"));
        assertEquals("${DB_PASSWORD:?set it on the server}", manifest.at("/services/api/environment/DB_PASSWORD").asText());

        Files.writeString(compose, contents + "    volumes: ['./local.conf:/etc/config']\n");
        assertThrows(ComposeTemplateStepExecutor.StepFailure.class, () -> executor.renderManifest(compose, spec, images));
    }

    private static RuntimeServiceInstance instance(Long host, String project) {
        var instance = new RuntimeServiceInstance();
        instance.setInstanceType("DOCKER_COMPOSE");
        instance.setHostId(host);
        instance.setInstanceName(project);
        return instance;
    }
}
