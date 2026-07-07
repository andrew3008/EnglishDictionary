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
import space.br1440.platform.devtools.opusmcp.prompt.DesignClassHierarchyPromptBuilder;
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

class DesignClassHierarchyToolSecurityTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private DesignClassHierarchyTool tool(OpusClient client, RateLimiter rateLimiter,
            BudgetTracker budget) {
        return new DesignClassHierarchyTool(
                config(), client, new DesignClassHierarchyPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), rateLimiter, budget, new AuditLogger());
    }

    private RateLimiter open() {
        return new RateLimiter(0);
    }

    private BudgetTracker unlimited() {
        return new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
    }

    private Map<String, Object> args(String domainContext, String existingTypes, String packageContext,
            String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Design a class hierarchy");
        m.put("language", "java");
        m.put("domainContext", domainContext);
        m.put("existingTypes", existingTypes);
        m.put("packageContext", packageContext);
        m.put("constraints", constraints);
        m.put("designGoal", "extensibility");
        m.put("scope", "module");
        m.put("architectureStyle", "clean_architecture");
        m.put("riskLevel", "high");
        m.put("outputFormat", "design_proposal");
        return m;
    }

    private static final String SAFE_CONTEXT = "A payments domain with multiple providers.";

    @Test
    void secretInDomainContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args("api_key=ABC123SECRETVALUE", "types", "pkg", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void privateKeyInExistingTypesRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_CONTEXT, "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
                        "pkg", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void tokenAssignmentInPackageContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_CONTEXT, "types", "password=SuperSecretValue123", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void dotEnvReferenceInConstraintsRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_CONTEXT, "types", "pkg", "please read .env"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void rateLimitExceededReturnsBudgetExceededAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        RateLimiter limiter = new RateLimiter(1, () -> 1000L);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("DESIGN_OVERVIEW:\nok\n", 1, 1));

        DesignClassHierarchyTool tool = tool(client, limiter, unlimited());
        assertThat(tool.handle(args(SAFE_CONTEXT, "t", "p", "c")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_CONTEXT, "t", "p", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyRequestLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("DESIGN_OVERVIEW:\nok\n", 1, 1));
        BudgetTracker budget = new BudgetTracker(new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d));

        DesignClassHierarchyTool tool = tool(client, open(), budget);
        assertThat(tool.handle(args(SAFE_CONTEXT, "t", "p", "c")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_CONTEXT, "t", "p", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutPrintingKey() throws OpusClientException {
        AppConfig noKey = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8"));
        OpusClient client = mock(OpusClient.class);
        DesignClassHierarchyTool tool = new DesignClassHierarchyTool(
                noKey, client, new DesignClassHierarchyPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), open(), unlimited(), new AuditLogger());

        var out = tool.handle(args(SAFE_CONTEXT, "t", "p", "c"));
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
        var out = tool(client, open(), unlimited()).handle(args(SAFE_CONTEXT, "t", "p", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void provider401MapsToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));
        var out = tool(client, open(), unlimited()).handle(args(SAFE_CONTEXT, "t", "p", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }
}
