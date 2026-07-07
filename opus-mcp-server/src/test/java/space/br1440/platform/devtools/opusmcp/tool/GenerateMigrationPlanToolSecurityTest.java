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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateMigrationPlanPromptBuilder;
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

class GenerateMigrationPlanToolSecurityTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private GenerateMigrationPlanTool tool(OpusClient client, RateLimiter rateLimiter, BudgetTracker budget) {
        return new GenerateMigrationPlanTool(
                config(), client, new GenerateMigrationPlanPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), rateLimiter, budget, new AuditLogger());
    }

    private RateLimiter open() {
        return new RateLimiter(0);
    }

    private BudgetTracker unlimited() {
        return new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
    }

    private Map<String, Object> args(String currentState, String migrationContext, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Plan a framework upgrade");
        m.put("language", "java");
        m.put("currentState", currentState);
        m.put("targetState", "Spring Boot 3.3");
        m.put("migrationContext", migrationContext);
        m.put("constraints", constraints);
        m.put("compatibilityMode", "preserve_api");
        m.put("migrationScope", "starter");
        m.put("migrationType", "framework_upgrade");
        m.put("riskLevel", "high");
        m.put("outputFormat", "migration_slices");
        return m;
    }

    private static final String SAFE = "Spring Boot 2.7 baseline";

    @Test
    void dotEnvInCurrentStateRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited()).handle(args("please read .env", SAFE, "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void privateKeyInMigrationContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited()).handle(args(SAFE,
                "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void secretInConstraintsRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE, "ctx", "password=SuperSecretValue123"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void readFileIntentInConstraintsRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited()).handle(args(SAFE, "ctx", "read .env please"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void rateLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        RateLimiter limiter = new RateLimiter(1, () -> 1000L);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("MIGRATION_OVERVIEW:\nok\n", 1, 1));
        GenerateMigrationPlanTool tool = tool(client, limiter, unlimited());
        assertThat(tool.handle(args(SAFE, "ctx", "c")).status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyRequestLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("MIGRATION_OVERVIEW:\nok\n", 1, 1));
        BudgetTracker budget = new BudgetTracker(new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d));
        GenerateMigrationPlanTool tool = tool(client, open(), budget);
        assertThat(tool.handle(args(SAFE, "ctx", "c")).status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutPrintingKey() throws OpusClientException {
        AppConfig noKey = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8"));
        OpusClient client = mock(OpusClient.class);
        GenerateMigrationPlanTool tool = new GenerateMigrationPlanTool(
                noKey, client, new GenerateMigrationPlanPromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), open(), unlimited(), new AuditLogger());
        var out = tool.handle(args(SAFE, "ctx", "c"));
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
        var out = tool(client, open(), unlimited()).handle(args(SAFE, "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void provider401MapsToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));
        var out = tool(client, open(), unlimited()).handle(args(SAFE, "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }
}
