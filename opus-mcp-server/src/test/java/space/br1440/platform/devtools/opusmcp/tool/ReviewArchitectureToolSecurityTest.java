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
import space.br1440.platform.devtools.opusmcp.prompt.ReviewArchitecturePromptBuilder;
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

class ReviewArchitectureToolSecurityTest {

    private AppConfig config() {
        return new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
    }

    private ReviewArchitectureTool tool(OpusClient client, RateLimiter rateLimiter,
            BudgetTracker budget) {
        return new ReviewArchitectureTool(
                config(), client, new ReviewArchitecturePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), rateLimiter, budget, new AuditLogger());
    }

    private RateLimiter open() {
        return new RateLimiter(0);
    }

    private BudgetTracker unlimited() {
        return new BudgetTracker(BudgetTracker.BudgetLimits.disabled());
    }

    private Map<String, Object> args(String proposal, String context, String constraints) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Review the proposed architecture");
        m.put("architectureProposal", proposal);
        m.put("context", context);
        m.put("constraints", constraints);
        m.put("reviewFocus", "all");
        m.put("architectureScope", "module");
        m.put("architectureStyle", "spring_boot_starter");
        m.put("compatibilityMode", "preserve_api");
        m.put("riskLevel", "high");
        m.put("outputFormat", "structured_review");
        return m;
    }

    private static final String SAFE_PROPOSAL = "Split the starter into core and autoconfigure modules.";

    @Test
    void secretInProposalRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args("api_key=ABC123SECRETVALUE", "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void privateKeyInContextRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_PROPOSAL, "-----BEGIN PRIVATE KEY-----\nabc\n-----END PRIVATE KEY-----",
                        "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void passwordAssignmentInConstraintsRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_PROPOSAL, "ctx", "password=SuperSecretValue123"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void dotEnvReferenceInConstraintsRefusedAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        var out = tool(client, open(), unlimited())
                .handle(args(SAFE_PROPOSAL, "ctx", "please read .env"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        verify(client, never()).generate(any(OpusRequest.class));
    }

    @Test
    void rateLimitExceededReturnsBudgetExceededAndModelNotCalled() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        RateLimiter limiter = new RateLimiter(1, () -> 1000L);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("REVIEW:\nok\n", 1, 1));

        ReviewArchitectureTool tool = tool(client, limiter, unlimited());
        assertThat(tool.handle(args(SAFE_PROPOSAL, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_PROPOSAL, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void dailyRequestLimitExceededReturnsBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("REVIEW:\nok\n", 1, 1));
        BudgetTracker budget = new BudgetTracker(new BudgetTracker.BudgetLimits(1, 0, 0, 0d, 0d, 0d));

        ReviewArchitectureTool tool = tool(client, open(), budget);
        assertThat(tool.handle(args(SAFE_PROPOSAL, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.OK);
        assertThat(tool.handle(args(SAFE_PROPOSAL, "ctx", "c")).status())
                .isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void missingApiKeyReturnsModelErrorWithoutPrintingKey() throws OpusClientException {
        AppConfig noKey = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8"));
        OpusClient client = mock(OpusClient.class);
        ReviewArchitectureTool tool = new ReviewArchitectureTool(
                noKey, client, new ReviewArchitecturePromptBuilder(), new SecretScanner(),
                new DenyList(), new LimitsGuard(10_000, 5_000, 10_000), new ModelRegistry(),
                new ErrorMapper(), open(), unlimited(), new AuditLogger());

        var out = tool.handle(args(SAFE_PROPOSAL, "ctx", "c"));
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
        var out = tool(client, open(), unlimited()).handle(args(SAFE_PROPOSAL, "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void provider401MapsToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));
        var out = tool(client, open(), unlimited()).handle(args(SAFE_PROPOSAL, "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.verdict()).isEmpty();
        assertThat(out.review()).isEmpty();
        assertThat(out.findings()).isEmpty();
        assertThat(out.safetyNotes()).isNotEmpty();
    }

    @Test
    void providerTimeoutDoesNotMasqueradeAsNeedsMoreContext() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.TIMEOUT, "timeout"));
        var out = tool(client, open(), unlimited()).handle(args(SAFE_PROPOSAL, "ctx", "c"));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).containsIgnoringCase("timed out");
        assertThat(out.summary()).doesNotContain("Invalid or insufficient input");
        assertThat(out.verdict()).isEmpty();
        assertThat(out.review()).isEmpty();
        assertThat(out.findings()).isEmpty();
        assertThat(out.risks()).anyMatch(r -> r.toLowerCase().contains("timeout"));
        assertThat(out.safetyNotes()).anyMatch(n -> n.toLowerCase().contains("not reflect"));
    }

    @Test
    void providerTimeoutJsonDoesNotLeakSecretsOrPrompt() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.TIMEOUT, "timeout"));
        String json = tool(client, open(), unlimited())
                .handleAsJson(args(SAFE_PROPOSAL, "ctx", "c"));
        assertThat(json).doesNotContain("secret-key-value");
        assertThat(json).doesNotContain("core and autoconfigure");
        assertThat(json).doesNotContain("timeout detail from provider");
    }
}
