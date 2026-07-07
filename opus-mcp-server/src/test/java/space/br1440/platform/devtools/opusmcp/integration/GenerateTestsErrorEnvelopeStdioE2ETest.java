package space.br1440.platform.devtools.opusmcp.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;
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
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Stdio E2E for Anthropic provider error envelopes: mocked HTTP 200 + {@code type=error} body must
 * surface as {@code MODEL_ERROR} at the MCP {@code tools/call} boundary without leaking raw provider
 * messages.
 */
class GenerateTestsErrorEnvelopeStdioE2ETest {

    private static final String ERROR_ENVELOPE_JSON = "{\"type\":\"error\",\"error\":"
            + "{\"type\":\"not_found_error\",\"message\":\"model: claude-opus-secret-model not found\"},"
            + "\"request_id\":\"req_secret123\"}";

    private final ObjectMapper mapper = new ObjectMapper();

    private ObjectNode structuredArguments() {
        ObjectNode args = mapper.createObjectNode();
        args.put("task", "Generate regression tests for tracing baseline");
        args.put("language", "java");
        args.put("code", "public class T { public static int v() { return 1; } }");
        args.put("testFramework", "junit5");
        args.put("testType", "regression");
        args.put("coverageFocus", "edge_cases");
        args.put("riskLevel", "low");
        args.put("outputFormat", "test_code");
        return args;
    }

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private GenerateTestsTool tool(OpusClient client) {
        return new GenerateTestsTool(
                config(), client, new GenerateTestsPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                new RateLimiter(0), new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    /**
     * Simulates a gateway returning HTTP 200 with an Anthropic error envelope; delegates parsing to
     * {@link AnthropicHttpOpusClient#parseResponse}.
     */
    private static final class ErrorEnvelopeOpusClient implements OpusClient {
        private final AnthropicHttpOpusClient httpClient;
        private final String providerJsonBody;
        final AtomicInteger calls = new AtomicInteger();

        ErrorEnvelopeOpusClient(AnthropicHttpOpusClient httpClient, String providerJsonBody) {
            this.httpClient = httpClient;
            this.providerJsonBody = providerJsonBody;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            calls.incrementAndGet();
            return httpClient.parseResponse(providerJsonBody, request);
        }
    }

    @Test
    void wireShapedToolCallWithAnthropicErrorEnvelopeReturnsModelError() throws Exception {
        ObjectNode request = mapper.createObjectNode();
        request.put("jsonrpc", "2.0");
        request.put("id", 99);
        request.put("method", "tools/call");
        ObjectNode params = request.putObject("params");
        params.put("name", GenerateTestsTool.TOOL_NAME);
        params.set("arguments", structuredArguments());

        String rpc = mapper.writeValueAsString(request);
        JsonNode argumentsNode = mapper.readTree(rpc).path("params").path("arguments");
        @SuppressWarnings("unchecked")
        Map<String, Object> arguments = mapper.convertValue(argumentsNode, LinkedHashMap.class);

        AnthropicHttpOpusClient httpClient = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        ErrorEnvelopeOpusClient client = new ErrorEnvelopeOpusClient(httpClient, ERROR_ENVELOPE_JSON);

        String json = tool(client).handleAsJson(arguments);
        JsonNode out = mapper.readTree(json);

        assertThat(out.path("status").asText()).isEqualTo("MODEL_ERROR");
        assertThat(out.path("summary").asText()).contains("Could not parse the provider response");
        assertThat(out.path("testCode").asText()).isEmpty();
        assertThat(out.path("inputTokenEstimate").asInt()).isZero();
        assertThat(client.calls.get()).isEqualTo(1);
        assertThat(json).doesNotContain("claude-opus-secret-model");
        assertThat(json).doesNotContain("req_secret123");
        assertThat(json).doesNotContain("secret-key-value");
    }

    @Test
    @Timeout(120)
    void realStdioToolCallWithMockProviderErrorEnvelopeReturnsModelError() throws Exception {
        AtomicInteger httpCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            httpCalls.incrementAndGet();
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = ERROR_ENVELOPE_JSON.getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("request-id", "req_header_smoke");
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
            exchange.close();
        });
        server.start();
        String baseUrl = "http://127.0.0.1:" + server.getAddress().getPort();

        ProcessBuilder pb = new ProcessBuilder(
                javaBin(),
                "-cp", System.getProperty("java.class.path"),
                "space.br1440.platform.devtools.opusmcp.Main");
        pb.redirectErrorStream(false);
        Map<String, String> env = pb.environment();
        env.keySet().removeIf(k -> k.startsWith("OPUS_"));
        env.put("OPUS_BASE_URL", baseUrl);
        env.put("OPUS_API_KEY", "test-key-smoke");
        env.put("OPUS_MODEL", "claude-opus-4-8");
        env.put("OPUS_RETRY_MAX_ATTEMPTS", "1");

        Process process = pb.start();
        BlockingQueue<String> stdoutLines = new LinkedBlockingQueue<>();
        List<String> stderrLines = Collections.synchronizedList(new ArrayList<>());
        Thread outReader = drain(process, true, stdoutLines, null);
        Thread errReader = drain(process, false, null, stderrLines);

        try {
            OutputStream stdin = process.getOutputStream();
            send(stdin, "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":"
                    + "{\"protocolVersion\":\"2024-11-05\",\"capabilities\":{},"
                    + "\"clientInfo\":{\"name\":\"it\",\"version\":\"1.0\"}}}");
            send(stdin, "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}");

            ObjectNode call = mapper.createObjectNode();
            call.put("jsonrpc", "2.0");
            call.put("id", 9);
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
                mapper.readTree(line);
                if (line.contains("\"id\":9")) {
                    callResponse = line;
                }
            }

            assertThat(callResponse).as("tools/call response; received=%s", received).isNotNull();
            JsonNode text = mapper.readTree(callResponse)
                    .path("result").path("content").path(0).path("text");
            assertThat(text.isTextual()).isTrue();
            JsonNode payload = mapper.readTree(text.asText());

            assertThat(payload.path("status").asText()).isEqualTo("MODEL_ERROR");
            assertThat(payload.path("summary").asText()).contains("Could not parse the provider response");
            assertThat(payload.path("testCode").asText()).isEmpty();
            assertThat(payload.path("inputTokenEstimate").asInt()).isZero();
            assertThat(httpCalls.get()).isEqualTo(1);

            String stderr = String.join("\n", stderrLines);
            assertThat(stderr).contains("envelopeKind=error_envelope");
            assertThat(stderr).contains("diagnosticCategory=error_envelope");
            assertThat(stderr).contains("providerCallAttempted=true");
            assertThat(stderr).doesNotContain("claude-opus-secret-model");
            assertThat(callResponse).doesNotContain("claude-opus-secret-model");
            assertThat(callResponse).doesNotContain("req_secret123");

            stdin.close();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroy();
                if (!process.waitFor(10, TimeUnit.SECONDS)) {
                    process.destroyForcibly();
                }
            }
        } finally {
            server.stop(0);
            process.destroyForcibly();
            outReader.join(5_000);
            errReader.join(5_000);
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

    private Thread drain(Process process, boolean stdout,
            BlockingQueue<String> queue, List<String> sink) {
        Thread t = new Thread(() -> {
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                    stdout ? process.getInputStream() : process.getErrorStream(),
                    StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.isBlank()) {
                        if (queue != null) {
                            queue.add(line);
                        }
                        if (sink != null) {
                            sink.add(line);
                        }
                    }
                }
            } catch (IOException ignored) {
                // Stream closed on process exit.
            }
        }, stdout ? "err-env-stdout" : "err-env-stderr");
        t.setDaemon(true);
        t.start();
        return t;
    }
}
