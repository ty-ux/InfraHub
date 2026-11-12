package com.infrahub.release.pipeline;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.infrahub.common.exception.BizException;
import com.infrahub.pipeline.entity.PipelineRun;
import com.infrahub.pipeline.mapper.PipelineRunMapper;
import com.infrahub.pipeline.service.PipelineStepLogStore;
import com.infrahub.release.entity.ReleaseArtifact;
import com.infrahub.release.entity.ReleaseVersion;
import com.infrahub.release.mapper.ReleaseArtifactMapper;
import com.infrahub.release.mapper.ReleaseVersionMapper;
import com.infrahub.release.service.ReleaseArtifactUploadService;
import com.infrahub.release.service.RuntimeServiceInstanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class RegisterArtifactPipelineStepExecutorTest {
    @TempDir Path directory;
    private final PipelineRunMapper runs = mock(PipelineRunMapper.class);
    private final ReleaseArtifactMapper artifacts = mock(ReleaseArtifactMapper.class);
    private final ReleaseVersionMapper versions = mock(ReleaseVersionMapper.class);

    @Test
    void bindsOneVersionAndArtifactToTheRunAcrossRetries() throws Exception {
        PipelineRun persisted = activeRun();
        PipelineRun execution = activeRun();
        PipelineStepLogStore store = new PipelineStepLogStore(directory.toString());
        Path descriptor = store.artifactDescriptor(persisted.getId());
        Files.createDirectories(descriptor.getParent());
        Files.writeString(descriptor, """
                {"artifactType":"DOCKER_COMPOSE","artifactName":"app.compose.yml","artifactUri":"/opt/app/incoming/71.compose.yml",
                 "checksum":"sha256:%s","gitCommit":"%s"}
                """.formatted("a".repeat(64), "b".repeat(40)));
        when(runs.lockById(71L)).thenReturn(persisted);
        when(artifacts.insert(any(ReleaseArtifact.class))).thenAnswer(invocation -> {
            invocation.getArgument(0, ReleaseArtifact.class).setId(105L);
            return 1;
        });
        when(versions.insert(any(ReleaseVersion.class))).thenAnswer(invocation -> {
            ReleaseVersion version = invocation.getArgument(0);
            version.setId(106L);
            when(versions.selectById(106L)).thenReturn(version);
            return 1;
        });

        var executor = executor(store);
        assertTrue(executor.execute(execution, null, null).isSuccess());
        assertTrue(executor.execute(execution, null, null).isSuccess());
        assertEquals(106L, persisted.getVersionId());
        ReleaseVersion saved = versions.selectById(106L);
        assertEquals(71L, saved.getBuildPipelineRunId());
        assertEquals(105L, saved.getArtifactId());
        assertEquals("run-71", saved.getVersionNo());
        verify(artifacts, times(1)).insert(any(ReleaseArtifact.class));
        verify(versions, times(1)).insert(any(ReleaseVersion.class));
    }

    @Test
    void missingBuildDescriptorNeverCreatesAVersion() {
        when(runs.lockById(71L)).thenReturn(activeRun());
        var executor = executor(new PipelineStepLogStore(directory.toString()));
        assertThrows(BizException.class, () -> executor.execute(activeRun(), null, null));
        verifyNoInteractions(artifacts, versions);
    }

    private RegisterArtifactPipelineStepExecutor executor(PipelineStepLogStore store) {
        return new RegisterArtifactPipelineStepExecutor(new ObjectMapper(), store, runs, versions, artifacts,
                mock(ReleaseArtifactUploadService.class), mock(RuntimeServiceInstanceService.class));
    }

    private PipelineRun activeRun() {
        PipelineRun run = new PipelineRun();
        run.setId(71L);
        run.setAppId(32L);
        run.setTriggerBy(1L);
        run.setRunStatus("RUNNING");
        return run;
    }
}
