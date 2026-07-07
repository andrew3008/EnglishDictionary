package space.br1440.platform.tracing.semconv.lint.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CommandLineLinterTest {

    @Test
    void чистый_отчет_возвращает_код_0(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("spans.json");
        Files.writeString(input, """
                [
                  {
                    "name": "GET /api",
                    "kind": "SERVER",
                    "status": "OK",
                    "attributes": {
                      "platform.trace.type": "http_server",
                      "platform.trace.result": "success",
                      "http.request.method": "GET",
                      "http.response.status_code": "200"
                    },
                    "resource": {
                      "service.name": "demo",
                      "platform.c_group": "billing",
                      "platform.id": "demo-001"
                    }
                  }
                ]
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CommandLineLinter(new PrintStream(out), new PrintStream(err))
                .run(new String[]{"--input", input.toString()});

        assertThat(code).isZero();
        assertThat(out.toString(StandardCharsets.UTF_8)).contains("Проверено span'ов: 1, ошибок: 0");
    }

    @Test
    void отсутствие_обязательных_атрибутов_возвращает_код_1(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("spans.json");
        Files.writeString(input, """
                [{"name": "op", "kind": "INTERNAL", "status": "OK", "attributes": {}, "resource": {}}]
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CommandLineLinter(new PrintStream(out), new PrintStream(err))
                .run(new String[]{"--input", input.toString()});

        assertThat(code).isEqualTo(1);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .contains("PLATFORM_SERVICE_NAME_REQUIRED")
                .contains("[ERROR]");
    }

    @Test
    void отсутствие_аргумента_input_возвращает_код_2() {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int code = new CommandLineLinter(new PrintStream(out), new PrintStream(err)).run(new String[]{});

        assertThat(code).isEqualTo(2);
        assertThat(err.toString(StandardCharsets.UTF_8)).contains("--input");
    }

    @Test
    void warnings_as_errors_конвертирует_warning_в_код_возврата_1(@TempDir Path tempDir) throws IOException {
        Path input = tempDir.resolve("spans.json");
        Files.writeString(input, """
                [{
                  "name": "op", "kind": "INTERNAL", "status": "OK",
                  "attributes": {"platform.trace.type": "internal", "platform.trace.result": "success"},
                  "resource": {"service.name": "demo", "platform.c_group": "billing", "platform.id": "demo-001",
                               "deployment.environment.name": "exotic"}
                }]
                """);

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteArrayOutputStream err = new ByteArrayOutputStream();
        int strictCode = new CommandLineLinter(new PrintStream(out), new PrintStream(err))
                .run(new String[]{"--input", input.toString(), "--warnings-as-errors"});
        assertThat(strictCode).isEqualTo(1);

        ByteArrayOutputStream out2 = new ByteArrayOutputStream();
        int relaxedCode = new CommandLineLinter(new PrintStream(out2), new PrintStream(err))
                .run(new String[]{"--input", input.toString()});
        assertThat(relaxedCode).isZero();
    }
}
