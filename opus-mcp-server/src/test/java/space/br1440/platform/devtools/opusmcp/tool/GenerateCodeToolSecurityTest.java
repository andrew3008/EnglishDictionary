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
import space.br1440.platform.devtools.opusmcp.prompt.PromptBuilder;
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

class GenerateCodeToolSecurityTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private GenerateCodeTool tool(OpusClient client, RateLimiter rateLimiter, BudgetTracker budget) {
        return new GenerateCodeTool(
                config(),
                client,
                new PromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000),
                new ModelRegistry(),
                new ErrorMapper(),
                rateLimiter,
                budget,
                new AuditLogger());
    }

    private RateLimiter open() {
        return new RateLimiter(0);
    }

    private BudgetTracker unlimited() {
        return new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
    }

    private Map<String, Object> args(String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate a Java method that adds two integers");
        m.put("language", "java");
        m.put("context", context);
        m.put("constraints", "Java 21");
        m.put("outputFormat", "code_block");
        m.put("riskLevel", "low");
        return m;
    }

    @Test
    void privateKeyInContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var output = tool(client, open(), unlimited())
                .handle(args("-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void secretAssignmentRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var output = tool(client, open(), unlimited()).handle(args("api_key=ABC123XYZ"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void dotEnvReferenceRefused() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var output = tool(client, open(), unlimited()).handle(args("look at .env file"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void idRsaReferenceRefused() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var output = tool(client, open(), unlimited()).handle(args("copy ~/.ssh/id_rsa"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void safeTaskIsAllowed() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("public int add(int a,int b){return a+b;}", 10, 8));
        var output = tool(client, open(), unlimited()).handle(args("no repository context"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.OK);
    }

    @Test
    void rateLimitExceededReturnsBudgetExceededAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        RateLimiter limiter = new RateLimiter(1, () -> 1000L);
        BudgetTracker budget = unlimited();
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("ok", 1, 1));

        GenerateCodeTool tool = tool(client, limiter, budget);
        assertThat(tool.handle(args("no repository context")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        var second = tool.handle(args("no repository context"));
        assertThat(second.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyRequestLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("ok", 1, 1));
        BudgetTracker budget = new BudgetTracker(
                new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d));

        GenerateCodeTool tool = tool(client, open(), budget);
        assertThat(tool.handle(args("no repository context")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args("no repository context")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyInputCharLimitExceededReturnsBudgetExceeded() {
        OpusClient client = mock(OpusClient.class);
        BudgetTracker budget = new BudgetTracker(
                new BudgetTracker.BudgetLimits(0, 5, 0, 0d, 0d, 0d));
        var output = tool(client, open(), budget).handle(args("no repository context"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutPrintingKey() throws OpusClientException {
        AppConfig noKey = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8"));
        OpusClient client = mock(OpusClient.class);
        GenerateCodeTool tool = new GenerateCodeTool(
                noKey, client, new PromptBuilder(), new SecretScanner(), new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(), new ErrorMapper(),
                open(), unlimited(), new AuditLogger());

        var output = tool.handle(args("no repository context"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(output.summary()).contains("OPUS_API_KEY");
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void provider401MapsToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));
        var output = tool(client, open(), unlimited()).handle(args("no repository context"));
        assertThat(output.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void successUpdatesBudgetCounters() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("ok", 11, 7));
        BudgetTracker budget = unlimited();
        tool(client, open(), budget).handle(args("no repository context"));
        assertThat(budget.snapshot().requestCount()).isEqualTo(1);
        assertThat(budget.snapshot().estimatedInputTokens()).isEqualTo(11);
        assertThat(budget.snapshot().estimatedOutputTokens()).isEqualTo(7);
    }
}
