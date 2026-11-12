package com.infrahub.infra.ssh;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class CliSshCommandTransportTest {
    @TempDir Path temporary;

    @Test
    void nativeCommandLineContainsNoQuotesNewlinesOrUnexpandedScript() {
        String command = CliSshExecutorService.encodeCommandForCli("value=\"a b\"\nprintf '%s\\n' \"$value\"\n");
        assertFalse(command.contains("\""));
        assertFalse(command.contains("'"));
        assertFalse(command.contains("\n"));
        assertFalse(command.contains("$"));
        assertFalse(command.contains("a b"));
    }

    @Test
    void realShellPreservesJavaVersionQuotesEmptyPidAndUtf8() throws Exception {
        Path bash = Path.of("D:/Program Files/Git/bin/bash.exe");
        if (!Files.isExecutable(bash)) bash = Path.of("/bin/bash");
        assumeTrue(Files.isExecutable(bash), "POSIX shell needed for transport regression");
        String script = """
                JAVA_VERSION_OUTPUT='openjdk version "17.0.15" 2025-04-15'
                JAVA_MAJOR="$(printf '%s\\n' "$JAVA_VERSION_OUTPUT" | sed -E 's/.*version "([0-9]+).*/\\1/')"
                test "$JAVA_MAJOR" = 17 || exit 41
                PORT_PID=""
                if [ -n "$PORT_PID" ]; then exit 42; fi
                printf '%s\\n' '版本检查通过: "17" $PID `literal`'
                exit 7
                """;
        Path output = temporary.resolve("stdout.txt");
        Path error = temporary.resolve("stderr.txt");
        Process process = new ProcessBuilder(bash.toString(), "-c",
                CliSshExecutorService.encodeCommandForCli(script))
                .redirectOutput(output.toFile()).redirectError(error.toFile()).start();
        try {
            assertTrue(process.waitFor(10, TimeUnit.SECONDS));
            assertEquals(7, process.exitValue(), Files.readString(error));
            assertEquals("版本检查通过: \"17\" $PID `literal`\n",
                    Files.readString(output, StandardCharsets.UTF_8).replace("\r\n", "\n"));
        } finally {
            process.destroyForcibly();
        }
    }
}
