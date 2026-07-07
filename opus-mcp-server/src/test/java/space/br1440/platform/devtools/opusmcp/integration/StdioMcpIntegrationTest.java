package space.br1440.platform.devtools.opusmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CI-friendly stdio integration test. Launches the real {@code Main} server in a child JVM using the
 * current test classpath (no fat-jar required, no real network, no real API key) and drives it with
 * raw JSON-RPC over stdio.
 *
 * <p>Determinism/safety: OPUS_* env is stripped from the child; only {@code initialize},
 * {@code tools/list}, and {@code echo_mcp_connection} are exercised (none touch the network); the
 * process is force-terminated in cleanup; stdout is asserted to contain only JSON.
 */
class StdioMcpIntegrationTest {

    private final ObjectMapper mapper = new ObjectMapper();

    private static String javaBin() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "")
                .toLowerCase(Locale.ROOT).contains("win");
        File bin = new File(home, "bin/java" + (windows ? ".exe" : ""));
        return bin.getAbsolutePath();
    }

    @Test
    @Timeout(120)
    void serverInitializesListsToolsAndEchoesOverStdio() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                javaBin(),
                "-cp", System.getProperty("java.class.path"),
                "space.br1440.platform.devtools.opusmcp.Main");
        pb.redirectErrorStream(false);
        // Strip provider env for determinism and to guarantee no accidental network use.
        pb.environment().keySet().removeIf(k -> k.startsWith("OPUS_"));

        Process process = pb.start();
        BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        List<String> stderrLines = new ArrayList<>();
        Thread outReader = drain(process, true, stdoutLines, null);
        Thread errReader = drain(process, false, null, stderrLines);

        try {
            OutputStream stdin = process.getOutputStream();
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\",\"params\":{}}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":"
                    + "{\"name\":\"echo_mcp_connection\",\"arguments\":{\"message\":\"hello-phase-5\"}}}");

            // Collect JSON-RPC response lines (order-independent), matching by id.
            List<String> received = new ArrayList<>();
            String initResponse = null;
            String toolsListResponse = null;
            String echoResponse = null;
            for (int i = 0; i < 8 && (initResponse == null || toolsListResponse == null
                    || echoResponse == null); i++) {
                String line = poll(stdoutLines);
                if (line == null) {
                    break;
                }
                received.add(line);
                // Every stdout line must be valid JSON (no log text leaks onto stdout).
                mapper.readTree(line); // throws if not valid JSON
                if (line.contains("\"id\":1")) {
                    initResponse = line;
                } else if (line.contains("\"id\":2")) {
                    toolsListResponse = line;
                } else if (line.contains("\"id\":3")) {
                    echoResponse = line;
                }
            }

            assertThat(initResponse).as("initialize response; received=%s", received)
                    .isNotNull();
            assertThat(initResponse).contains("\"serverInfo\"").contains("java-mcp-opus-server");
            assertThat(toolsListResponse).as("tools/list response; received=%s", received)
                    .isNotNull();
            assertThat(toolsListResponse)
                    .contains("echo_mcp_connection")
                    .contains("generate_code_with_opus")
                    .contains("review_code_with_opus")
                    .contains("generate_tests_with_opus")
                    .contains("refactor_plan_with_opus")
                    .contains("explain_diff_with_opus")
                    .contains("research_with_perplexity")
                    .contains("analyze_build_failure_with_opus")
                    .contains("design_class_hierarchy_with_opus")
                    .contains("review_architecture_with_opus")
                    .contains("write_mdx_doc_with_opus")
                    .contains("review_mdx_doc_with_opus")
                    .contains("generate_migration_plan_with_opus")
                    .contains("review_tests_with_opus")
                    .contains("review_gradle_build_with_opus");
            assertThat(echoResponse).as("echo response; received=%s", received)
                    .isNotNull();
            // The echo payload is JSON nested as escaped text inside result.content[0].text.
            JsonNode echoText = mapper.readTree(echoResponse)
                    .path("result").path("content").path(0).path("text");
            assertThat(echoText.isTextual()).as("echo content text present").isTrue();
            JsonNode echoPayload = mapper.readTree(echoText.asText());
            assertThat(echoPayload.path("status").asText()).isEqualTo("OK");
            assertThat(echoPayload.path("echo").asText()).isEqualTo("hello-phase-5");
            assertThat(echoPayload.path("phase").asText()).isEqualTo("0A");

            stdin.close();
            boolean exited = process.waitFor(30, TimeUnit.SECONDS);
            if (!exited) {
                process.destroy();
                exited = process.waitFor(10, TimeUnit.SECONDS);
                if (!exited) {
                    process.destroyForcibly();
                }
            }
        } finally {
            process.destroyForcibly();
            outReader.join(5_000);
            errReader.join(5_000);
        }
    }

    private void send(OutputStream stdin, String json) throws IOException {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private String poll(BlockingQueue<String> lines) throws InterruptedException {
        return lines.poll(30, TimeUnit.SECONDS);
    }

    private Thread drain(Process process, boolean stdout,
            BlockingQueue<String> queue, List<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    stdout ? process.getInputStream() : process.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.isBlank()) {
                        continue;
                    }
                    if (queue != null) {
                        queue.add(line);
                    }
                    if (sink != null) {
                        synchronized (sink) {
                            sink.add(line);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closed on process exit.
            }
        }, stdout ? "it-stdout" : "it-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
