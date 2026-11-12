package com.infrahub.pipeline.service;

import com.infrahub.pipeline.entity.PipelineStepRun;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;

@Component
public class PipelineStepLogStore {

    private static final int CHUNK_SIZE = 64 * 1024;
    private final Path root;

    public PipelineStepLogStore(@Value("${infrahub.pipeline.log-directory:./logs/pipeline}") String directory) {
        root = Path.of(directory).toAbsolutePath().normalize();
    }

    public Path create(PipelineStepRun step) throws IOException {
        Path log = path(step);
        Files.createDirectories(log.getParent());
        return Files.createFile(log);
    }

    public Path artifactDescriptor(Long runId) {
        return root.resolve(runId.toString()).resolve("artifact.json");
    }

    public void saveResultIfMissing(PipelineStepRun step, String output) throws IOException {
        Path log = path(step);
        if (Files.exists(log)) return;
        Files.createDirectories(log.getParent());
        try {
            Files.writeString(log, output, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (FileAlreadyExistsException ignored) {
            // The executor's output takes precedence over the completed result.
        }
    }

    public LogChunk read(PipelineStepRun step, long offset, boolean terminal) throws IOException {
        try (SeekableByteChannel file = Files.newByteChannel(path(step), StandardOpenOption.READ)) {
            long size = file.size();
            long start = Math.min(offset, size);
            file.position(start);
            byte[] bytes = Channels.newInputStream(file).readNBytes((int) Math.min(CHUNK_SIZE, size - start));
            long nextOffset = start + bytes.length;
            boolean hasMore = nextOffset < size;
            return new LogChunk(true, Base64.getEncoder().encodeToString(bytes), nextOffset, hasMore, terminal && !hasMore);
        } catch (NoSuchFileException missing) {
            return new LogChunk(false, "", 0, false, terminal);
        }
    }

    public static String preview(Path log, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(log)) {
            return new String(input.readNBytes(limit), StandardCharsets.UTF_8);
        }
    }

    public static String tail(Path log, int limit) throws IOException {
        try (InputStream input = Files.newInputStream(log)) {
            input.skipNBytes(Math.max(0, Files.size(log) - limit));
            return new String(input.readNBytes(limit), StandardCharsets.UTF_8).strip();
        }
    }

    private Path path(PipelineStepRun step) {
        return root.resolve(step.getPipelineRunId().toString()).resolve(step.getId() + ".log");
    }

    public record LogChunk(boolean available, String base64, long nextOffset, boolean hasMore, boolean finished) {
    }
}
