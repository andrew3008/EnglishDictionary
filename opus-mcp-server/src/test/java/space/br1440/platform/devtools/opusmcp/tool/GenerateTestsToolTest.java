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
import space.br1440.platform.devtools.opusmcp.prompt.GenerateTestsPromptBuilder;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsOutput;
import space.br1440.platform.devtools.opusmcp.tool.dto.GeneratedTestCase;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateTestsToolTest {

    private static final class FakeOpusClient implements OpusClient {
        final AtomicReference<OpusRequest> last = new AtomicReference<>();
        final java.util.concurrent.atomic.AtomicInteger calls =
                new java.util.concurrent.atomic.AtomicInteger();
        final String text;

        FakeOpusClient(String text) {
            this.text = text;
        }

        @Override
        public OpusResponse generate(OpusRequest request) throws OpusClientException {
            calls.incrementAndGet();
            last.set(request);
            return new OpusResponse(text, 13, 9);
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

    private Map<String, Object> args(String code, String context) {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate unit tests for add");
        m.put("language", "java");
        m.put("code", code);
        m.put("context", context);
        m.put("constraints", "Java 21, no external libraries");
        m.put("testFramework", "junit5");
        m.put("testType", "unit");
        m.put("coverageFocus", "edge_cases");
        m.put("riskLevel", "medium");
        m.put("outputFormat", "structured_tests");
        return m;
    }

    private static final String STRUCTURED = """
            SUMMARY:
            JUnit 5 tests covering overflow and happy path for add.

            TEST_PLAN:
            Cover normal addition and integer overflow boundaries.

            TEST_CODE:
            ```java
            @Test
            void addsTwoNumbers() {
                assertThat(add(2, 3)).isEqualTo(5);
            }
            ```

            TEST_CASES:
            - name: adds two positive numbers
              type: unit
              priority: HIGH
              purpose: verify normal addition
              given: a=2, b=3
              when: add(a,b)
              then: result is 5
            - name: overflow throws or wraps
              type: property
              priority: MEDIUM
              purpose: document overflow behavior
              given: a=MAX_VALUE, b=1
              when: add(a,b)
              then: result overflows

            RISKS:
            - Overflow behavior is implementation-defined

            SAFETY_NOTES:
            - No file or command side effects

            ASSUMPTIONS:
            - add is a pure static method

            TESTS_TO_RUN:
            - ./gradlew test --tests AddTest
            """;

    @Test
    void okResponseParsesAllSections() {
        GenerateTestsOutput out = tool(new FakeOpusClient(STRUCTURED))
                .handle(args("public static int add(int a,int b){return a+b;}", "no repo context"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.summary()).contains("JUnit 5 tests");
        assertThat(out.testPlan()).contains("integer overflow").doesNotContain("TEST_PLAN:");
        assertThat(out.testCode()).contains("assertThat(add(2, 3))").doesNotContain("TEST_CODE:");
        assertThat(out.testCases()).hasSize(2);

        GeneratedTestCase first = out.testCases().get(0);
        assertThat(first.name()).isEqualTo("adds two positive numbers");
        assertThat(first.type()).isEqualTo("unit");
        assertThat(first.priority()).isEqualTo("HIGH");
        assertThat(first.purpose()).contains("normal addition");
        assertThat(first.given()).contains("a=2");
        assertThat(first.when()).contains("add(a,b)");
        assertThat(first.then()).contains("result is 5");

        assertThat(out.testCases().get(1).type()).isEqualTo("property");
        assertThat(out.risks()).containsExactly("Overflow behavior is implementation-defined");
        assertThat(out.safetyNotes()).containsExactly("No file or command side effects");
        assertThat(out.assumptions()).containsExactly("add is a pure static method");
        assertThat(out.testsToRun()).containsExactly("./gradlew test --tests AddTest");
        assertThat(out.inputTokenEstimate()).isEqualTo(13);
        assertThat(out.outputTokenEstimate()).isEqualTo(9);
        assertThat(out.model()).isEqualTo("claude-opus-4-8");
    }

    @Test
    void testCasesWithUnknownTypeAndPriorityFallBackToDefaults() {
        String text = "TEST_PLAN:\nplan\n\nTEST_CASES:\n- name: weird\n  type: bogus\n  priority: urgent\n"
                + "  purpose: p\n";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.testCases()).hasSize(1);
        assertThat(out.testCases().get(0).type()).isEqualTo("other");
        assertThat(out.testCases().get(0).priority()).isEqualTo("MEDIUM");
    }

    @Test
    void malformedTestCaseDoesNotAbortParsingOfOthers() {
        String text = "TEST_PLAN:\nplan\n\nTEST_CASES:\n- just a bare bullet case\n"
                + "- name: real case\n  type: integration\n";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.testCases()).hasSize(2);
        assertThat(out.testCases().get(0).name()).isEqualTo("just a bare bullet case");
        assertThat(out.testCases().get(1).name()).isEqualTo("real case");
        assertThat(out.testCases().get(1).type()).isEqualTo("integration");
    }

    @Test
    void nonCompliantResponseStillReturnsTextInTestPlan() {
        String text = "Here is a freeform test idea without any sections at all.";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(args("int x=1;", ""));
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testPlan()).contains("freeform test idea");
        assertThat(out.testCases()).isEmpty();
    }

    /**
     * Regression for the reported bug: the exact structured Cursor request (notably
     * {@code testType=regression} and {@code outputFormat=test_code}) must pass validation, reach the
     * mocked provider exactly once, and return {@code OK}. Previously the tool bound {@code testType}
     * against the review-tests enum, which has no {@code regression} value, so this failed with
     * {@code NEEDS_MORE_CONTEXT} before any provider call.
     */
    @Test
    void exactCursorStructuredRequestReachesProviderOnceAndReturnsOk() {
        FakeOpusClient client = new FakeOpusClient("TEST_PLAN:\nCharacterization plan\n");
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate Phase 0 characterization tests for tracing control wire v1 behavior");
        m.put("language", "java");
        m.put("code", "minimal valid test context");
        m.put("testFramework", "junit5");
        m.put("testType", "regression");
        m.put("coverageFocus", "edge_cases");
        m.put("riskLevel", "high");
        m.put("outputFormat", "test_code");

        GenerateTestsOutput out = tool(client).handle(m);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(client.calls.get()).isEqualTo(1);
    }

    @Test
    void schemaAdvertisedEnumValuesAllBindToProvider() {
        for (String framework : new String[] {"junit5", "testng", "mockito", "assertj",
                "spring_boot_test", "kotest", "go_test", "other"}) {
            FakeOpusClient client = new FakeOpusClient("TEST_PLAN:\nok\n");
            Map<String, Object> m = args("int x=1;", "ctx");
            m.put("testFramework", framework);
            m.put("testType", "regression");
            m.put("outputFormat", "test_code");
            assertThat(tool(client).handle(m).status())
                    .as("framework %s", framework)
                    .isEqualTo(GenerateCodeStatus.OK);
            assertThat(client.calls.get()).as("framework %s calls", framework).isEqualTo(1);
        }
        for (String type : new String[] {"unit", "integration", "contract", "slice", "property",
                "regression", "all"}) {
            FakeOpusClient client = new FakeOpusClient("TEST_PLAN:\nok\n");
            Map<String, Object> m = args("int x=1;", "ctx");
            m.put("testType", type);
            assertThat(tool(client).handle(m).status()).as("type %s", type)
                    .isEqualTo(GenerateCodeStatus.OK);
            assertThat(client.calls.get()).as("type %s calls", type).isEqualTo(1);
        }
    }

    @Test
    void missingRequiredFieldDoesNotCallProviderAndReportsMissingKeys() {
        FakeOpusClient client = new FakeOpusClient("unused");
        Map<String, Object> m = args("int x=1;", "ctx");
        m.remove("task");
        m.remove("code");

        GenerateTestsOutput out = tool(client).handle(m);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
        assertThat(client.calls.get()).isZero();
        // Diagnostic quality: the summary names the missing field KEYS (never values).
        assertThat(out.summary()).contains("task").contains("code");
    }

    @Test
    void validateInputExposesReceivedAndMissingKeysWithoutValues() {
        Map<String, Object> m = new HashMap<>();
        m.put("language", "java");
        m.put("code", "public class A {}");
        m.put("testFramework", "junit5");
        m.put("testType", "regression");
        m.put("coverageFocus", "edge_cases");
        m.put("riskLevel", "high");
        m.put("outputFormat", "test_code");
        // 'task' intentionally absent.

        GenerateTestsTool.InputValidation v = tool(new FakeOpusClient("unused")).validateInput(m);

        assertThat(v.input()).isNull();
        assertThat(v.missingFields()).containsExactly("task");
        assertThat(v.receivedKeys())
                .contains("language", "code", "testFramework", "testType", "coverageFocus",
                        "riskLevel", "outputFormat")
                .doesNotContain("task");
        // receivedKeys/missingFields carry KEYS only, never the field values.
        assertThat(String.join(",", v.receivedKeys())).doesNotContain("public class A");
    }

    @Test
    void invalidInputReturnsNeedsMoreContext() {
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate tests");
        m.put("language", "java");
        m.put("code", ""); // blank code
        m.put("testFramework", "junit5");
        m.put("testType", "unit");
        m.put("coverageFocus", "all");
        m.put("riskLevel", "low");
        m.put("outputFormat", "test_code");
        GenerateTestsOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void invalidEnumReturnsNeedsMoreContext() {
        Map<String, Object> m = args("int x=1;", "");
        m.put("testFramework", "not-a-framework");
        GenerateTestsOutput out = tool(new FakeOpusClient("unused")).handle(m);
        assertThat(out.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void codeIsForwardedToModelPrompt() {
        FakeOpusClient client = new FakeOpusClient("TEST_PLAN:\nok\n");
        tool(client).handle(args("public int q(){return 42;}", "no repo context"));
        assertThat(client.last.get().userPrompt()).contains("public int q(){return 42;}");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("read-only");
        assertThat(client.last.get().systemPrompt().toLowerCase()).contains("do not run tests");
    }

    // ---- Provider-response parser robustness for outputFormat=test_code -----------------------

    private Map<String, Object> testCodeArgs(String code) {
        Map<String, Object> m = args(code, "Slice 0A baseline context");
        m.put("testType", "regression");
        m.put("coverageFocus", "all");
        m.put("riskLevel", "low");
        m.put("outputFormat", "test_code");
        return m;
    }

    private static final String SAMPLE_CLASS = """
            package space.br1440.platform.tracing.otel;

            import org.junit.jupiter.api.Test;

            class DefaultPlatformTracingBaselineTest {
                @Test
                void baseline() {
                }
            }""";

    // Shape A: canonical sectioned response with a fenced TEST_CODE block.
    @Test
    void shapeA_canonicalSectionedResponseParsesTestCodeWithoutHeading() {
        String text = "SUMMARY:\nBaseline GREEN tests.\n\nTEST_PLAN:\nLock v1 behavior.\n\n"
                + "TEST_CODE:\n```java\n" + SAMPLE_CLASS + "\n```\n\nTEST_CASES:\n- name: baseline\n";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("package space.br1440.platform.tracing.otel;");
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("TEST_CODE");
    }

    // Shape B: fenced Java only, no section headers.
    @Test
    void shapeB_fencedJavaOnlyResponseIsParsedAsTestCode() {
        FakeOpusClient client = new FakeOpusClient("```java\n" + SAMPLE_CLASS + "\n```");
        GenerateTestsOutput out = tool(client).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("package space.br1440.platform.tracing.otel;");
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("```");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    // Shape C: short prose followed by a fenced Java block.
    @Test
    void shapeC_proseAndFencedJavaResponseIsParsedAsTestCode() {
        String text = "Here is the requested test class:\n\n```java\n" + SAMPLE_CLASS + "\n```";
        FakeOpusClient client = new FakeOpusClient(text);
        GenerateTestsOutput out = tool(client).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("Here is the requested test class");
        assertThat(out.summary()).isNotBlank();
        assertThat(client.calls.get()).isEqualTo(1);
    }

    // Shape D: plain Java source starting with package, no fences, no sections.
    @Test
    void shapeD_plainJavaSourceResponseIsParsedAsTestCode() {
        FakeOpusClient client = new FakeOpusClient(SAMPLE_CLASS);
        GenerateTestsOutput out = tool(client).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("package space.br1440.platform.tracing.otel;");
        assertThat(out.testCode()).contains("import org.junit.jupiter.api.Test;");
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(client.calls.get()).isEqualTo(1);
    }

    // Shape E: malformed non-code, non-section response.
    @Test
    void shapeE_malformedResponseReturnsModelErrorWithSafeDiagnostic() {
        FakeOpusClient client = new FakeOpusClient("I cannot help with that.");
        GenerateTestsOutput out = tool(client).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(out.summary()).contains("Could not parse provider response");
        // The raw provider text must never leak into the summary.
        assertThat(out.summary()).doesNotContain("I cannot help with that");
        assertThat(out.testCode()).isEmpty();
        assertThat(client.calls.get()).isEqualTo(1);
        // Token estimates from the provider response are preserved (not zeroed) on parse failure.
        assertThat(out.inputTokenEstimate()).isEqualTo(13);
        assertThat(out.outputTokenEstimate()).isEqualTo(9);
    }

    // Phase 3: CRLF line endings in a fenced Java response.
    @Test
    void phase3_crlfFencedJavaResponseIsParsedAsTestCode() {
        String crlfBody = SAMPLE_CLASS.replace("\n", "\r\n");
        String text = "```java\r\n" + crlfBody + "\r\n```";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("package space.br1440.platform.tracing.otel;");
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("\r");
    }

    // Phase 3: four-backtick fence (variable fence length).
    @Test
    void phase3_fourTickFenceExtractsJavaTestCode() {
        String text = "````java\n" + SAMPLE_CLASS + "\n````";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("````");
    }

    // Phase 3: triple backticks inside a string literal must not close the fence early.
    @Test
    void phase3_backticksInStringLiteralDoNotCloseFence() {
        String javaWithBackticks = """
                package p;

                class T {
                    String marker = "```not a fence```";

                    @Test
                    void ok() {
                    }
                }""";
        String text = "```java\n" + javaWithBackticks + "\n```";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("```not a fence```");
        assertThat(out.testCode()).contains("@Test");
    }

    // Phase 3: unlabeled fence with Java-like body is accepted.
    @Test
    void phase3_unlabeledFenceWithJavaBodyIsParsedAsTestCode() {
        String text = "```\n" + SAMPLE_CLASS + "\n```";
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
    }

    // Phase 3: CRLF plain Java source without fences.
    @Test
    void phase3_crlfPlainJavaSourceIsParsedAsTestCode() {
        String text = SAMPLE_CLASS.replace("\n", "\r\n");
        GenerateTestsOutput out = tool(new FakeOpusClient(text)).handle(testCodeArgs("class C {}"));

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(out.testCode()).doesNotContain("\r");
    }

    @Test
    void phase3_normalizeForParsingStripsBomAndNormalizesLineEndings() {
        assertThat(GenerateTestsTool.normalizeForParsing("\uFEFFline1\r\nline2\rline3"))
                .isEqualTo("line1\nline2\nline3");
    }

    @Test
    void phase3_extractPreferredJavaFencePrefersJavaLabeledBlock() {
        String text = "```text\nnot java\n```\n\n```java\n" + SAMPLE_CLASS + "\n```";
        Optional<String> code = GenerateTestsTool.extractPreferredJavaFence(text);

        assertThat(code).isPresent();
        assertThat(code.orElseThrow()).contains("class DefaultPlatformTracingBaselineTest");
    }

    // Regression for the reported user scenario: full Java class only -> OK, provider called once.
    @Test
    void userScenarioFullJavaClassResponseReachesProviderOnceAndReturnsOk() {
        FakeOpusClient client = new FakeOpusClient("```java\n" + SAMPLE_CLASS + "\n```");
        Map<String, Object> m = new HashMap<>();
        m.put("task", "Generate JUnit test class DefaultPlatformTracingBaselineTest for Slice 0A");
        m.put("language", "java");
        m.put("code", "package space.br1440.platform.tracing.otel; // v1 methods under test");
        m.put("context", "Plan Slice 0A: Baseline GREEN tests locking v1 DefaultPlatformTracing behavior");
        m.put("constraints", "No production code. No Gradle. No knownDefectTest");
        m.put("coverageFocus", "all");
        m.put("testFramework", "junit5");
        m.put("testType", "regression");
        m.put("riskLevel", "low");
        m.put("outputFormat", "test_code");

        GenerateTestsOutput out = tool(client).handle(m);

        assertThat(out.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(out.testCode()).contains("class DefaultPlatformTracingBaselineTest");
        assertThat(client.calls.get()).isEqualTo(1);
    }
}
