package dev.playcity.timemachine.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TimeMachineCliTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void printsHelpWithoutRequiringPaperAtRuntime() {
        CliResult result = run("--lang", "en", "--help");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("offline restore CLI"));
        assertTrue(result.output().contains("restore export"));
        assertEquals("", result.error());
    }

    @Test
    void rejectsUnknownOptionsWithAUsageExitCode() {
        CliResult result = run("--lang", "en", "restore", "list", "--unknown", "value");

        assertEquals(2, result.exitCode());
        assertTrue(result.error().contains("Unknown option"));
    }

    @Test
    void listsAnEmptyExplicitStore() {
        CliResult result = run(
                "--lang",
                "en",
                "restore",
                "list",
                "--store",
                temporaryDirectory.resolve("backups").toString());

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("No snapshots found"));
    }

    @Test
    void printsKoreanHelpWhenLanguageIsSelectedAnywhere() {
        CliResult result = run("--help", "--lang=ko");

        assertEquals(0, result.exitCode());
        assertTrue(result.output().contains("오프라인 복원 CLI"));
        assertTrue(result.output().contains("사용법"));
        assertEquals("", result.error());
    }

    @Test
    void localizesDetailedVerificationFailuresInKorean() {
        CliResult result = run(
                "--lang=ko",
                "restore",
                "verify",
                "--snapshot",
                "missing-snapshot",
                "--store",
                temporaryDirectory.resolve("backups").toString());

        assertEquals(3, result.exitCode());
        assertTrue(result.error().contains("스냅샷을 찾을 수 없습니다: missing-snapshot"));
        assertTrue(!result.error().contains("Snapshot was not found"));
    }

    private CliResult run(String... args) {
        ByteArrayOutputStream outputBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errorBytes = new ByteArrayOutputStream();
        try (PrintStream output = new PrintStream(outputBytes, true, StandardCharsets.UTF_8);
                PrintStream error = new PrintStream(errorBytes, true, StandardCharsets.UTF_8)) {
            int exitCode = TimeMachineCli.run(args, output, error);
            return new CliResult(
                    exitCode,
                    outputBytes.toString(StandardCharsets.UTF_8),
                    errorBytes.toString(StandardCharsets.UTF_8));
        }
    }

    private record CliResult(int exitCode, String output, String error) {
    }
}
