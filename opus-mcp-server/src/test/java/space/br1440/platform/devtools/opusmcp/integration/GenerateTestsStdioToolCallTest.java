package space.br1440.platform.devtools.opusmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.model.OpusClient;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusRequest;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for {@code generate_tests_with_opus} at the MCP {@code tools/call} boundary,
 * using the exact structured argument shape a Cursor client sends.
 *
 * <p>This is the most important regression for the "all required fields missing" false negative: the
 * structured arguments (notably {@code testType=regression}, {@code outputFormat=test_code}) must bind
 * through the MCP {@code params.arguments} object and reach the model provider.
 *
 * <ul>
 *   <li>{@link #wireShapedToolCallArgumentsReachMockedProviderExactlyOnce()} drives the exact
 *       {@code params.arguments} map (deserialized from a real JSON-RPC {@code tools/call} envelope)
 *       into the tool with a mocked provider and asserts a single provider call with {@code OK}. This
 *       mirrors {@code McpServerFactory#handleGenerateTests}, which forwards
 *       {@code request.arguments()} verbatim to the tool.</li>
 *   <li>{@link #realStdioToolCallPassesValidationWithoutProviderCall()} launches the real {@code Main}
 *       server over stdio (OPUS_* env stripped, so no real network/provider) and asserts the same
 *       structured arguments pass validation (status is {@code MODEL_ERROR} from the config gate, never
 *       {@code NEEDS_MORE_CONTEXT}).</li>
 * </ul>
 */
class GenerateTestsStdioToolCallTest {

    private final ObjectMapper mapper = new ObjectMapper();

    /** The exact structured arguments a Cursor client would send for this tool. */
    private ObjectNode structuredArguments() {
        ObjectNode args = mapper.createObjectNode();
        args.put("task", "Generate Phase 0 characterization tests for tracing control wire v1 behavior");
        args.put("language", "java");
        args.put("code", "minimal valid test context");
        args.put("testFramework", "junit5");
        args.put("testType", "regression");
        args.put("coverageFocus", "edge_cases");
        args.put("riskLevel", "high");
        args.put("outputFormat", "test_code");
        return args;
    }

    private static final class CountingOpusClient implements OpusClient {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicReference<OpusRequest> last = new AtomicReference<>();

        @Override
        public OpusResponse generate(OpusRequest request) {
            calls.incrementAndGet();
            last.set(request);
            return new OpusResponse("TEST_PLAN:\nCharacterization baseline\n", 11, 7);
        }
    }

    private GenerateTestsTool tool(OpusClient client) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new GenerateTestsTool(
                config, client, new GenerateTestsPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    @Test
    void wireShapedToolCallArgumentsReachMockedProviderExactlyOnce() throws Exception {
        // Build a real JSON-RPC tools/call envelope, then extract params.arguments as the SDK would.
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 42);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", GenerateTestsTool.TOOL_NAME);
        params.set("arguments", structuredArguments());

        String rpc = mapper.writeValueAsString(request);
        JsonNode argumentsNode = mapper.readTree(rpc).path("params").path("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = mapper.convertValue(argumentsNode, LinkedHashMap.class);

        CountingOpusClient client = new CountingOpusClient();
        String json = tool(client).handleAsJson(arguments);

        JsonNode out = mapper.readTree(json);
        assertThat(out.path("status").asText()).isEqualTo("OK");
        assertThat(client.calls.get()).isEqualTo(1);
        // The provided code was forwarded to the model as data (never dropped by validation).
        assertThat(client.last.get().userPrompt()).contains("minimal valid test context");
        // No secret material leaks into the serialized tool output.
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    @Timeout(120)
    void realStdioToolCallPassesValidationWithoutProviderCall() throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                javaBin(),
                "-cp", System.getProperty("java.class.path"),
                "space.br1440.platform.devtools.opusmcp.Main");
        pb.redirectErrorStream(false);
        // Strip provider env: guarantees no real network/provider call regardless of local config.
        pb.environment().keySet().removeIf(k -> k.startsWith("OPUS_"));

        Process process = pb.start();
        BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        Thread outReader = drain(process, stdoutLines);

        try {
            OutputStream stdin = process.getOutputStream();
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            ObjectNode call = mapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", 7);
            call.put("method", "tools/call");
            ObjectNode params = call.putObject("params");
            params.put("name", GenerateTestsTool.TOOL_NAME);
            params.set("arguments", structuredArguments());
            send(stdin, mapper.writeValueAsString(call));

            String callResponse = null;
            List<String> received = new ArrayList<>();
            for (int i = 0; i < 12 && callResponse == null; i++) {
                String line = stdoutLines.poll(30, TimeUnit.SECONDS);
                if (line == null) {
                    break;
                }
                received.add(line);
                mapper.readTree(line); // every stdout line must be valid JSON (no log leakage)
                if (line.contains("\"id\":7")) {
                    callResponse = line;
                }
            }

            assertThat(callResponse).as("tools/call response; received=%s", received).isNotNull();
            JsonNode text = mapper.readTree(callResponse)
                    .path("result").path("content").path(0).path("text");
            assertThat(text.isTextual()).as("tool result text present").isTrue();
            JsonNode payload = mapper.readTree(text.asText());
            String status = payload.path("status").asText();

            // The structured arguments MUST pass validation: no NEEDS_MORE_CONTEXT false negative.
            assertThat(status).isNotEqualTo("NEEDS_MORE_CONTEXT");
            // With OPUS_* stripped, the request stops at the config gate (past validation) and never
            // reaches a provider: deterministic MODEL_ERROR, not OK.
            assertThat(status).isEqualTo("MODEL_ERROR");

            stdin.close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } finally {
            process.destroyForcibly();
            outReader.join(5_000);
        }
    }

    private static String javaBin() {
        String home = System.getProperty("java.home");
        boolean windows = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        return new File(home, "bin/java" + (windows ? ".exe" : "")).getAbsolutePath();
    }

    private void send(OutputStream stdin, String json) throws IOException {
        stdin.write((json + "\n").getBytes(StandardCharsets.UTF_8));
        stdin.flush();
    }

    private Thread drain(Process process, BlockingQueue<String> queue) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        queue.add(line);
                    }
                }
            } catch (IOException ignored) {
                // Stream closed on process exit.
            }
        }, "gen-tests-stdout");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
