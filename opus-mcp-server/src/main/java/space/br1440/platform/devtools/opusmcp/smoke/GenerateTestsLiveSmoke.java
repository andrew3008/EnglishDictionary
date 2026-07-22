package space.br1440.platform.devtools.opusmcp.smoke;

import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.AnthropicHttpOpusClient;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.tool.GenerateTestsTool;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsOutput;
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Manual live smoke for {@link GenerateTestsTool} with {@code outputFormat=test_code}. Not an MCP
 * tool; invokes the tool pipeline in-process with the real {@link AnthropicHttpOpusClient}.
 *
 * <p>Hard boundaries: synthetic non-proprietary input only; metadata-only stdout; never prints API
 * keys, prompts, or generated test code.
 */
public final class GenerateTestsLiveSmoke {

    private GenerateTestsLiveSmoke() {
    }

    public record SmokeResult(
            boolean ok,
            GenerateCodeStatus status,
            String summary,
            int inputTokenEstimate,
            int outputTokenEstimate,
            String model,
            String requestId,
            int testCodeLength,
            boolean testCodeHasPackage,
            boolean testCodeHasClass,
            String providerRequestId,
            String envelopeKind,
            String message) {
    }

    public static SmokeResult run(AppConfig config) {
        Optional<String> configError = validate(config);
        if (configError.isPresent()) {
            return new SmokeResult(false, GenerateCodeStatus.MODEL_ERROR, "", 0, 0, "", "",
                    0, false, false, "", "", configError.get());
        }

        AnthropicHttpOpusClient client = new AnthropicHttpOpusClient(config, new ModelRegistry());
        GenerateTestsTool tool = new GenerateTestsTool(
                config,
                client,
                new GenerateTestsPromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000),
                new ModelRegistry(),
                new ErrorMapper(),
                new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());

        GenerateTestsOutput out = tool.handle(liveArgs());
        String testCode = out.testCode() == null ? "" : out.testCode();
        boolean ok = out.status() == GenerateCodeStatus.OK && !testCode.isBlank();
        String message = ok
                ? "Live generate_tests_with_opus reached OK with populated testCode"
                : "Live smoke failed: status=" + out.status();
        return new SmokeResult(
                ok,
                out.status(),
                out.summary(),
                out.inputTokenEstimate(),
                out.outputTokenEstimate(),
                out.model(),
                out.requestId(),
                testCode.length(),
                testCode.contains("package "),
                testCode.matches("(?s).*\\bclass\\s+\\w+.*"),
                "",
                "",
                message);
    }

    static Map<String, Object> liveArgs() {
        Map<String, Object> args = new HashMap<>();
        args.put("task", "Generate JUnit test class DefaultPlatformTracingBaselineTest for Slice 0A");
        args.put("language", "java");
        args.put("code", "package space.br1440.platform.tracing.otel;"
                + " public class DefaultPlatformTracing { public static String name() { return \"v1\"; } }");
        args.put("context", "Plan Slice 0A: Baseline GREEN tests locking v1 behavior");
        args.put("constraints", "No production code. No Gradle.");
        args.put("testFramework", "junit5");
        args.put("testType", "regression");
        args.put("coverageFocus", "all");
        args.put("riskLevel", "low");
        args.put("outputFormat", "test_code");
        return args;
    }

    static Optional<String> validate(AppConfig config) {
        if (config.baseUrl().isEmpty()) {
            return Optional.of("Missing " + AppConfig.ENV_BASE_URL);
        }
        if (!config.hasApiKey()) {
            return Optional.of("Missing " + AppConfig.ENV_API_KEY);
        }
        return Optional.empty();
    }

    public static void main(String[] args) {
        AppConfig config = AppConfig.fromEnv();
        System.out.println("[live-smoke] generate_tests_with_opus (test_code, regression)");
        System.out.println("[live-smoke] model=" + config.model());
        System.out.println("[live-smoke] synthetic input only (no repository context)");

        SmokeResult result = run(config);
        System.out.println("[live-smoke] ok=" + result.ok()
                + " status=" + result.status()
                + " inputTokenEstimate=" + result.inputTokenEstimate()
                + " outputTokenEstimate=" + result.outputTokenEstimate()
                + " testCodeLength=" + result.testCodeLength()
                + " testCodeHasPackage=" + result.testCodeHasPackage()
                + " testCodeHasClass=" + result.testCodeHasClass());
        System.out.println("[live-smoke] summary=" + result.summary());
        System.out.println("[live-smoke] requestId=" + result.requestId());
        System.out.println("[live-smoke] " + result.message());
        System.exit(result.ok() ? 0 : 1);
    }
}
