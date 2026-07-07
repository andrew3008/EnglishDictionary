package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.config.AppConfig;
import space.br1440.platform.devtools.opusmcp.error.ErrorMapper;
import space.br1440.platform.devtools.opusmcp.model.ModelRegistry;
import space.br1440.platform.devtools.opusmcp.model.OpusClient;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.model.OpusRequest;
import space.br1440.platform.devtools.opusmcp.model.OpusResponse;
import space.br1440.platform.devtools.opusmcp.prompt.PromptBuilder;
import space.br1440.platform.devtools.opusmcp.audit.AuditLogger;
import space.br1440.platform.devtools.opusmcp.budget.BudgetTracker;
import space.br1440.platform.devtools.opusmcp.budget.RateLimiter;
import space.br1440.platform.devtools.opusmcp.security.DenyList;
import space.br1440.platform.devtools.opusmcp.security.SecretScanner;
import space.br1440.platform.devtools.opusmcp.security.LimitsGuard;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GenerateCodeToolTest {

    private GenerateCodeTool tool(OpusClient opusClient) {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "claude-opus-4-8",
                AppConfig.ENV_API_KEY, "secret-key-value"));
        return new GenerateCodeTool(
                config,
                opusClient,
                new PromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000),
                new ModelRegistry(),
                new ErrorMapper(),
                new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());
    }

    private Map<String, Object> validArgs() {
        return Map.of(
                "task", "Generate a Java method that adds two integers",
                "language", "java",
                "context", "no repository context",
                "constraints", "Java 21, no external libraries",
                "outputFormat", "code_block",
                "riskLevel", "low");
    }

    @Test
    void returnsOkWithMockedResponse() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("public int add(int a,int b){return a+b;}", 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(output.model()).isEqualTo("claude-opus-4-8");
        assertThat(output.result()).contains("public int add");
        assertThat(output.requestId()).isNotBlank();
    }

    @Test
    void returnsNeedsMoreContextForBlankTask() {
        OpusClient client = mock(OpusClient.class);
        Map<String, Object> args = Map.of(
                "task", "   ",
                "language", "java",
                "outputFormat", "code_block",
                "riskLevel", "low");

        var output = tool(client).handle(args);

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.NEEDS_MORE_CONTEXT);
    }

    @Test
    void returnsRefusedUnsafeForSecretInContext() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        Map<String, Object> args = Map.of(
                "task", "Generate code",
                "language", "java",
                "context", "password=super-secret",
                "outputFormat", "code_block",
                "riskLevel", "low");

        var output = tool(client).handle(args);

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
    }

    @Test
    void maps401ToModelError() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "auth", 401));

        var output = tool(client).handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(output.summary()).contains("Authentication");
    }

    @Test
    void maps429ToBudgetExceeded() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenThrow(new OpusClientException(OpusClientException.Reason.HTTP_ERROR, "rate", 429));

        var output = tool(client).handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void jsonOutputDoesNotExposeApiKey() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("ok", 1, 1));

        String json = tool(client).handleAsJson(validArgs());

        assertThat(json).doesNotContain("secret-key-value");
        assertThat(json).contains("\"status\":\"OK\"");
    }

    @Test
    void summaryIsNotCodeFenceWhenResultStartsWithFence() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("```java\npublic static int add(int a,int b){return a+b;}\n```", 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(output.summary())
                .doesNotStartWith("```")
                .isNotBlank()
                .contains("Java");
        // code_block result must still preserve the fenced code block.
        assertThat(output.result()).contains("```java").contains("public static int add");
    }

    @Test
    void summaryFallbackUsesTaskAndLanguage() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse("```\nsome code only\n```", 1, 1));

        var output = tool(client).handle(validArgs());

        assertThat(output.summary())
                .contains("Java")
                .contains("Generate a Java method that adds two integers");
    }

    @Test
    void explicitSummarySectionIsParsed() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        String modelText = "SUMMARY:\nAdds two integers and returns the sum.\n\n"
                + "RESULT:\n```java\npublic int add(int a,int b){return a+b;}\n```";
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(modelText, 5, 5));

        var output = tool(client).handle(validArgs());

        assertThat(output.summary()).isEqualTo("Adds two integers and returns the sum.");
        assertThat(output.result()).contains("```java");
    }

    @Test
    void sectionsStillParsedAlongsideSummary() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        String modelText = "SUMMARY:\nProposal ready.\n\nRESULT:\ncode\n\n"
                + "ASSUMPTIONS:\n- assumes Java 21\n\nRISKS:\n- none significant\n\n"
                + "SAFETY_NOTES:\n- no side effects\n\nTESTS_TO_RUN:\n- unit test add()";
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(modelText, 5, 5));

        var output = tool(client).handle(validArgs());

        assertThat(output.assumptions()).containsExactly("assumes Java 21");
        assertThat(output.risks()).containsExactly("none significant");
        assertThat(output.safetyNotes()).containsExactly("no side effects");
        assertThat(output.testsToRun()).containsExactly("unit test add()");
    }

    private static final String STRUCTURED_RESPONSE = "SUMMARY:\n"
            + "Provide a Java 21 method that returns the sum of two integers.\n\n"
            + "RESULT:\n```java\npublic static int add(int a, int b) {\n    return a + b;\n}\n```\n\n"
            + "ASSUMPTIONS:\n- assumes Java 21\n\n"
            + "RISKS:\n- integer overflow possible\n\n"
            + "SAFETY_NOTES:\n- no side effects\n\n"
            + "TESTS_TO_RUN:\n- unit test add(2,3)==5";

    @Test
    void resultExtractsOnlyResultSectionWhenStructuredResponseContainsAllSections()
            throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(STRUCTURED_RESPONSE, 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.OK);
        assertThat(output.result())
                .isEqualTo("```java\npublic static int add(int a, int b) {\n    return a + b;\n}\n```");
    }

    @Test
    void resultPreservesFencedCodeBlockInsideResultSection() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(STRUCTURED_RESPONSE, 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.result())
                .startsWith("```java")
                .endsWith("```")
                .contains("public static int add(int a, int b)")
                .contains("return a + b;");
    }

    @Test
    void resultFallsBackWhenResultSectionMissing() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        String noResultSection = "Here is a helpful explanation without explicit sections.";
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(noResultSection, 5, 5));

        var output = tool(client).handle(validArgs());

        assertThat(output.result()).isEqualTo(noResultSection);
    }

    @Test
    void resultIsNotEmptyForPlainCodeBlockResponse() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        String codeOnly = "```java\npublic int add(int a,int b){return a+b;}\n```";
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(codeOnly, 5, 5));

        var output = tool(client).handle(validArgs());

        assertThat(output.result()).isEqualTo(codeOnly);
        assertThat(output.result()).isNotBlank();
    }

    @Test
    void resultDoesNotContainSummaryAssumptionsRisksSafetyNotesTestsWhenStructuredSectionsExist()
            throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(STRUCTURED_RESPONSE, 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.result())
                .doesNotContain("SUMMARY:")
                .doesNotContain("ASSUMPTIONS:")
                .doesNotContain("RISKS:")
                .doesNotContain("SAFETY_NOTES:")
                .doesNotContain("TESTS_TO_RUN:");
    }

    @Test
    void summaryExtractionStillUsesExplicitSummary() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(STRUCTURED_RESPONSE, 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.summary())
                .isEqualTo("Provide a Java 21 method that returns the sum of two integers.");
        assertThat(output.summary()).doesNotStartWith("```").isNotBlank();
    }

    @Test
    void assumptionsRisksSafetyNotesTestsStillParsed() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        when(client.generate(any(OpusRequest.class)))
                .thenReturn(new OpusResponse(STRUCTURED_RESPONSE, 10, 8));

        var output = tool(client).handle(validArgs());

        assertThat(output.assumptions()).containsExactly("assumes Java 21");
        assertThat(output.risks()).containsExactly("integer overflow possible");
        assertThat(output.safetyNotes()).containsExactly("no side effects");
        assertThat(output.testsToRun()).containsExactly("unit test add(2,3)==5");
    }

    @Test
    void unsafeSecretInputStillRefusedBeforeModelCall() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        Map<String, Object> args = new java.util.HashMap<>(validArgs());
        args.put("context", "api_key=ABC123SECRETVALUE");

        var output = tool(client).handle(args);

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).generate(any(OpusRequest.class));
    }

    @Test
    void denyListInputStillRefusedBeforeModelCall() throws OpusClientException {
        OpusClient client = mock(OpusClient.class);
        Map<String, Object> args = new java.util.HashMap<>(validArgs());
        args.put("context", "please read .env");

        var output = tool(client).handle(args);

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.REFUSED_UNSAFE);
        org.mockito.Mockito.verify(client, org.mockito.Mockito.never()).generate(any(OpusRequest.class));
    }

    @Test
    void rejectsNonAllowlistedConfiguredModel() {
        AppConfig config = new AppConfig(Map.of(
                AppConfig.ENV_BASE_URL, "https://api.cheat-ai.shop",
                AppConfig.ENV_MODEL, "unknown-model",
                AppConfig.ENV_API_KEY, "k"));
        GenerateCodeTool generateTool = new GenerateCodeTool(
                config,
                mock(OpusClient.class),
                new PromptBuilder(),
                new SecretScanner(),
                new DenyList(),
                new LimitsGuard(10_000, 5_000, 10_000),
                new ModelRegistry(),
                new ErrorMapper(),
                new RateLimiter(0),
                new BudgetTracker(BudgetTracker.BudgetLimits.disabled()),
                new AuditLogger());

        var output = generateTool.handle(validArgs());

        assertThat(output.status()).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }
}
