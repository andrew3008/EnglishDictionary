package space.br1440.platform.devtools.opusmcp.integration;

import org.junit.jupiter.api.Test;
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
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end regression: OpenAI-style provider envelope → {@link AnthropicHttpOpusClient#extractText}
 * → {@link GenerateTestsTool} section/fence parser → {@code OK} with populated {@code testCode}.
 *
 * <p>Guards the two-layer pipeline that previously failed when the gateway returned
 * {@code choices[].message.content} instead of native Anthropic {@code content[].text}.
 */
class GenerateTestsEnvelopePipelineTest {

    private static final String SAMPLE_CLASS = """
            package space.br1440.platform.tracing.core;

            import org.junit.jupiter.api.Test;

            class DefaultPlatformTracingBaselineTest {
                @Test
                void baseline() {
                }
            }""";

    private static final String SECTIONED_RESPONSE = "SUMMARY:\nBaseline GREEN tests.\n\n"
            + "TEST_PLAN:\nLock v1 behavior.\n\n"
            + "TEST_CODE:\n```java\n" + SAMPLE_CLASS + "\n```\n\n"
            + "TEST_CASES:\n- name: baseline\n  type: regression\n";

    /**
     * OpusClient that simulates a gateway returning an OpenAI-style HTTP body: the raw JSON envelope
     * is parsed by {@link AnthropicHttpOpusClient#parseResponse} before the extracted text reaches
     * {@link GenerateTestsTool}.
     */
    private static final class EnvelopeOpusClient implements OpusClient {
        private final AnthropicHttpOpusClient httpClient;
        private final String providerJsonBody;
        final AtomicInteger calls = new AtomicInteger();

        EnvelopeOpusClient(AnthropicHttpOpusClient httpClient, String providerJsonBody) {
            this.httpClient = httpClient;
            this.providerJsonBody = providerJsonBody;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            calls.incrementAndGet();
            return httpClient.parseResponse(providerJsonBody, request);
        }
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

    private Map<String, Object> testCodeArgs() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate JUnit test class DefaultPlatformTracingBaselineTest for Slice 0A");
        m.put("language", "java");
        m.put("code", "package space.br1440.platform.tracing.core; // v1 methods under test");
        m.put("context", "Plan Slice 0A: Baseline GREEN tests");
        m.put("constraints", "No production code. No Gradle.");
        m.put("testFramework", "junit5");
        m.put("testType", "regression");
        m.put("coverageFocus", "all");
        m.put("riskLevel", "low");
        m.put("outputFormat", "test_code");
        return m;
    }

    @Test
    void openAiEnvelopeWithSectionedTestCodeReachesOkWithPopulatedTestCode() throws OpusClientException {
        // Escape the sectioned body for embedding in a JSON string value.
        String escapedContent = SECTIONED_RESPONSE
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String openAiEnvelope = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + escapedContent + "\"}}],"
                + "\"usage\":{\"input_tokens\":42,\"output_tokens\":128}}";

        AnthropicHttpOpusClient httpClient = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        EnvelopeOpusClient envelopeClient = new EnvelopeOpusClient(httpClient, openAiEnvelope);

        GenerateTestsOutput out = tool(envelopeClient).handle(testCodeArgs());

        assertThat(envelopeClient.calls.get()).isEqualTo(1);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("package space.br1440.platform.tracing.core;");
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("TEST_CODE");
        // Token estimates prove envelope parsing succeeded (non-zero, from usage block).
        assertThat(out.inputTokenEstimate()).isEqualTo(42);
        assertThat(out.outputTokenEstimate()).isEqualTo(128);
    }

    @Test
    void openAiEnvelopeWithOpenAiUsageFieldNamesReachesOk() throws OpusClientException {
        String escapedContent = SECTIONED_RESPONSE
                .replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n");
        String openAiEnvelope = "{\"choices\":[{\"message\":{\"role\":\"assistant\","
                + "\"content\":\"" + escapedContent + "\"}}],"
                + "\"usage\":{\"prompt_tokens\":55,\"completion_tokens\":99,\"total_tokens\":154}}";

        AnthropicHttpOpusClient httpClient = new AnthropicHttpOpusClient(config(), new ModelRegistry());
        EnvelopeOpusClient envelopeClient = new EnvelopeOpusClient(httpClient, openAiEnvelope);

        GenerateTestsOutput out = tool(envelopeClient).handle(testCodeArgs());

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.inputTokenEstimate()).isEqualTo(55);
        assertThat(out.outputTokenEstimate()).isEqualTo(99);
    }
}
