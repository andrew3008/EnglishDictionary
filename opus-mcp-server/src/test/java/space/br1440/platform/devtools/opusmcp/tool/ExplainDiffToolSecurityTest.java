package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
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
import space.br1440.platform.devtools.opusmcp.prompt.ExplainDiffPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ExplainDiffToolSecurityTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ExplainDiffTool tool(OpusClient client, RateLimiter rateLimiter, BudgetTracker budget) {
        return new ExplainDiffTool(
                config(), client, new ExplainDiffPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                rateLimiter, budget, new AuditLogger());
    }

    private RateLimiter open() {
        return new RateLimiter(0);
    }

    private BudgetTracker unlimited() {
        return new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
    }

    private Map<String, Object> args(String diff, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review this diff for security");
        m.put("language", "java");
        m.put("diff", diff);
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("diffFormat", "git_diff");
        m.put("analysisFocus", "security");
        m.put("riskLevel", "high");
        m.put("outputFormat", "merge_review");
        return m;
    }

    private static final String SAFE_DIFF =
            "--- a/Calc.java\n+++ b/Calc.java\n@@ -1 +1 @@\n-return a+b;\n+return a-b;";

    @Test
    void secretInDiffRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args("+String k = \"api_key=ABC123SECRETVALUE\";", "no repo context"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void privateKeyInDiffRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args("+-----BEGIN PRIVATE KEY-----\n+abc\n+-----END PRIVATE KEY-----", ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void dotEnvReferenceInContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited()).handle(args(SAFE_DIFF, "see .env file"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void rateLimitExceededReturnsBudgetExceededAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        RateLimiter limiter = new RateLimiter(1, () -> 1000L);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("EXPLANATION:\nok\n", 1, 1));

        ExplainDiffTool tool = tool(client, limiter, unlimited());
        assertThat(tool.handle(args(SAFE_DIFF, "ctx")).status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_DIFF, "ctx")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyRequestLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("EXPLANATION:\nok\n", 1, 1));
        BudgetTracker budget = new BudgetTracker(new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d));

        ExplainDiffTool tool = tool(client, open(), budget);
        assertThat(tool.handle(args(SAFE_DIFF, "ctx")).status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_DIFF, "ctx")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutPrintingKey() throws OpusClientException {
        AppConfig noKey = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8"));
        OpusClient client = mock(OpusClient.class);
        ExplainDiffTool tool = new ExplainDiffTool(
                noKey, client, new ExplainDiffPromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                open(), unlimited(), new AuditLogger());

        var out = tool.handle(args(SAFE_DIFF, "ctx"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).contains("OPUS_API_KEY");
        assertThat(out.summary()).doesNotContain("secret-key-value");
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void provider429MapsToBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "rate", 429));
        var out = tool(client, open(), unlimited()).handle(args(SAFE_DIFF, "ctx"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void provider401MapsToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));
        var out = tool(client, open(), unlimited()).handle(args(SAFE_DIFF, "ctx"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }
}
