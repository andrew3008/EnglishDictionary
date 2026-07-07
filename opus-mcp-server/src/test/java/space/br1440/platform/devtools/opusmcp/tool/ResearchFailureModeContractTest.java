package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityConfig;
import space.br1440.platform.devtools.opusmcp.perplexity.PerplexityDiagnosticCategory;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClient;
import space.br1440.platform.devtools.opusmcp.perplexity.ResearchClientException;
import space.br1440.platform.devtools.opusmcp.prompt.ResearchPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchOutput;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8D failure-mode contract: pins the user-visible output for every error path. Every error
 * result has a safe summary, an empty answer, no raw provider body, no API key, and no leakage of the
 * research question prompt. Offline only; no network.
 */
class ResearchFailureModeContractTest {

    private static final String API_KEY = "secret-key-value-DO-NOT-LEAK";
    private static final String QUESTION = "UNIQUE-RESEARCH-QUESTION-MARKER-12345";
    private static final String RAW_BODY = "RAW-PROVIDER-BODY-LEAK-9876";

    private PerplexityConfig keyConfig() {
        return new PerplexityConfig(Map.of(PerplexityConfig.ENV_API_KEY, API_KEY));
    }

    private ResearchWithPerplexityTool tool(ResearchClient client, PerplexityConfig config,
            RateLimiter rateLimiter, BudgetTracker budgetTracker) {
        return new ResearchWithPerplexityTool(
                config, client, new ResearchPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 50_000), rateLimiter, budgetTracker, new AuditLogger());
    }

    private Map<String, Object> args(String question, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "research task");
        m.put("researchQuestion", question);
        m.put("context", context);
        m.put("constraints", "");
        m.put("sourcePreference", "mixed");
        m.put("freshness", "stable");
        m.put("depth", "quick");
        m.put("outputFormat", "brief");
        m.put("riskLevel", "low");
        return m;
    }

    private ResearchClient throwing(PerplexityDiagnosticCategory category) {
        return (s, u) -> {
            throw new ResearchClientException(category, 500, RAW_BODY);
        };
    }

    private void assertSafe(ResearchOutput out, GenerateCodeStatus expected) {
        assertThat(out.status()).isEqualTo(expected);
        assertThat(out.summary()).doesNotContain(RAW_BODY);
        assertThat(out.summary()).doesNotContain(API_KEY);
        assertThat(out.summary()).doesNotContain(QUESTION);
        assertThat(out.answer()).isEmpty();
        assertThat(out.sources()).isEmpty();
    }

    private ResearchOutput runWithProviderError(PerplexityDiagnosticCategory category) {
        return tool(throwing(category), keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled())).handle(args(QUESTION, ""));
    }

    @Test
    void missingKey() {
        ResearchOutput out = tool((s, u) -> {
            throw new IllegalStateException("provider must not be called");
        }, new PerplexityConfig(Map.of()), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled())).handle(args(QUESTION, ""));
        assertSafe(out, GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).contains("PERPLEXITY_API_KEY");
        assertThat(out.safetyNotes()).containsExactly("No provider call was made.");
    }

    @Test
    void authError() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.AUTH_ERROR),
                GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void modelNotFound() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.MODEL_NOT_FOUND),
                GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void rateLimitFromProvider() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA),
                GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void provider5xx() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.PROVIDER_DOWN),
                GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void networkTimeout() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.NETWORK_ERROR),
                GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void parseError() {
        assertSafe(runWithProviderError(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR),
                GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void budgetExceeded() {
        BudgetTracker exhausted = new BudgetTracker(new BudgetTracker.BudgetLimits(
                1, 0, 0, 0d, 0d, 0d));
        exhausted.record(0, 0, 0);
        ResearchClient client = (s, u) -> {
            throw new IllegalStateException("provider must not be called");
        };
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0), exhausted)
                .handle(args(QUESTION, ""));
        assertSafe(out, GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void rateLimiterExceeded() {
        RateLimiter limiter = new RateLimiter(1);
        assertThat(limiter.tryAcquire()).isTrue();
        ResearchClient client = (s, u) -> {
            throw new IllegalStateException("provider must not be called");
        };
        ResearchOutput out = tool(client, keyConfig(), limiter,
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled())).handle(args(QUESTION, ""));
        assertSafe(out, GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void secretRefusedBeforeProvider() {
        ResearchClient client = (s, u) -> {
            throw new IllegalStateException("provider must not be called");
        };
        ResearchOutput out = tool(client, keyConfig(), new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()))
                .handle(args(QUESTION, "api_key=ABC123SECRETVALUE"));
        assertSafe(out, GenerateCodeStatus.REFUSED_UNSAFE);
    }
}
