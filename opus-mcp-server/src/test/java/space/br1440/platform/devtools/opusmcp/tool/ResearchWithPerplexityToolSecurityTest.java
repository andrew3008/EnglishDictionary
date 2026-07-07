package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityConfig;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityDiagnosticCategory;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClient;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClientException;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchResponse;
import space.br1440.platform.devtools.opusmcp.prompt.ResearchPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchOutput;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class ResearchWithPerplexityToolSecurityTest {

    private static final class CountingClient implements ResearchClient {
        final AtomicInteger calls = new AtomicInteger();

        @Override
        public ResearchResponse research(String systemPrompt, String userPrompt) {
            calls.incrementAndGet();
            return new ResearchResponse("ANSWER:\nfine\n", 1, 1, "sonar-deep-research", "rid");
        }
    }

    private PerplexityConfig keyConfig() {
        return new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ResearchWithPerplexityTool tool(ResearchClient client, PerplexityConfig config,
            RateLimiter rateLimiter, BudgetTracker budgetTracker) {
        return new ResearchWithPerplexityTool(
                config, client, new ResearchPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 50_000), rateLimiter, budgetTracker, new AuditLogger());
    }

    private Map<String, Object> args(String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "research task");
        m.put("researchQuestion", "What is dependency injection?");
        m.put("context", context);
        m.put("constraints", "");
        m.put("sourcePreference", "mixed");
        m.put("freshness", "stable");
        m.put("depth", "quick");
        m.put("outputFormat", "brief");
        m.put("riskLevel", "low");
        return m;
    }

    @Test
    void secretInContextIsRefusedBeforeProviderCall() {
        CountingClient client = new CountingClient();
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("api_key=ABC123SECRETVALUE"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void envReferenceIsRefusedBeforeProviderCall() {
        CountingClient client = new CountingClient();
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("please read .env for config"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void envReferenceInResearchQuestionIsRefusedBeforeProviderCall() {
        CountingClient client = new CountingClient();
        Map<String, Object> a = args("general public question");
        a.put("researchQuestion", "please read .env and summarize the config");
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled())).handle(a);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void privateKeyBlockInContextIsRefusedBeforeProviderCall() {
        CountingClient client = new CountingClient();
        String pem = "-----BEGIN PRIVATE KEY-----\nMIIBVAIBADANBgkqhkiG9w0BAQEFAASCAT\n"
                + "-----END PRIVATE KEY-----";
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled())).handle(args(pem));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void missingKeyResultExposesNoNetworkUxFields() {
        CountingClient client = new CountingClient();
        ResearchOutput out = tool(client, new PerplexityConfig(Map.of()), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("general public question"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.safetyNotes()).containsExactly("No provider call was made.");
        assertThat(out.followUpQuestions())
                .anySatisfy(q -> assertThat(q).contains("PERPLEXITY_API_KEY"));
        assertThat(out.answer()).isEmpty();
        assertThat(out.sources()).isEmpty();
        assertThat(out.inputTokenEstimate()).isZero();
        assertThat(out.outputTokenEstimate()).isZero();
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void rateLimitExceededReturnsBudgetExceededBeforeProviderCall() {
        CountingClient client = new CountingClient();
        // permitsPerMinute=1: first acquire used here, tool's acquire is throttled.
        RateLimiter limiter = new RateLimiter(1);
        assertThat(limiter.tryAcquire()).isTrue();
        ResearchOutput out = tool(client, keyConfig(), limiter,
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("general public question"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void budgetExceededReturnsBudgetExceededBeforeProviderCall() {
        CountingClient client = new CountingClient();
        BudgetTracker exhausted = new BudgetTracker(new BudgetTracker.BudgetLimits(
                1, 0, 0, 0d, 0d, 0d));
        exhausted.record(0, 0, 0); // consume the only daily request.
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0), exhausted)
                .handle(args("general public question"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void missingKeyReturnsModelErrorAndNoProviderCall() {
        CountingClient client = new CountingClient();
        ResearchOutput out = tool(client, new PerplexityConfig(Map.of()), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("general public question"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).contains("PERPLEXITY_API_KEY is not set");
        assertThat(client.calls.get()).isZero();
    }

    @Test
    void providerErrorsAreMappedSafelyWithoutLeakingBody() {
        for (PerplexityDiagnosticCategory category : new PerplexityDiagnosticCategory[] {
                PerplexityDiagnosticCategory.AUTH_ERROR,
                PerplexityDiagnosticCategory.MODEL_NOT_FOUND,
                PerplexityDiagnosticCategory.REQUEST_SHAPE_ERROR,
                PerplexityDiagnosticCategory.PROVIDER_DOWN,
                PerplexityDiagnosticCategory.NETWORK_ERROR}) {
            ResearchClient throwing = (s, u) -> {
                throw new ResearchClientException(category, 500, "RAW-PROVIDER-BODY-LEAK");
            };
            ResearchOutput out = tool(throwing, keyConfig(), new RateLimiter(0),
                    new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                    .handle(args("general public question"));
            assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
            assertThat(out.summary()).doesNotContain("RAW-PROVIDER-BODY-LEAK");
        }
    }

    @Test
    void providerRateLimitMapsToBudgetExceeded() {
        ResearchClient throwing = (s, u) -> {
            throw new ResearchClientException(PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA, 429,
                    "quota");
        };
        ResearchOutput out = tool(throwing, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args("general public question"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }
}

