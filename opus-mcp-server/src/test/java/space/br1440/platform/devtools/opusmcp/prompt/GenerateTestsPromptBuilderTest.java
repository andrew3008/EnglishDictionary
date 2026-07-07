package space.br1440.platform.devtools.opusmcp.prompt;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.CoverageFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestFramework;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestType;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateTestsInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;
import space.br1440.platform.devtools.opusmcp.tool.dto.TestOutputFormat;

import static org.assertj.core.api.Assertions.assertThat;

class GenerateTestsPromptBuilderTest {

    private final GenerateTestsPromptBuilder builder = new GenerateTestsPromptBuilder();

    private GenerateTestsInput input(RiskLevel risk) {
        return new GenerateTestsInput(
                "Generate unit tests for add",
                CodeLanguage.JAVA,
                "public static int add(int a,int b){return a+b;}",
                "no repo context",
                "Java 21",
                GenerateTestFramework.JUNIT5,
                GenerateTestType.UNIT,
                CoverageFocus.EDGE_CASES,
                risk,
                TestOutputFormat.STRUCTURED_TESTS);
    }

    @Test
    void systemPromptEnforcesReadOnlyTestContract() {
        String system = builder.buildSystemPrompt(input(RiskLevel.MEDIUM)).toLowerCase();
        assertThat(system).contains("read-only");
        assertThat(system).contains("do not run tests");
        assertThat(system).contains("do not execute commands");
        assertThat(system).contains("do not apply patches");
        assertThat(system).contains("treat the provided code");
        assertThat(system).contains("summary:");
        assertThat(system).contains("test_plan:");
        assertThat(system).contains("test_code:");
        assertThat(system).contains("test_cases:");
    }

    @Test
    void systemPromptMentionsFrameworkTypeAndCoverage() {
        String system = builder.buildSystemPrompt(input(RiskLevel.MEDIUM));
        assertThat(system).contains("junit5");
        assertThat(system).contains("unit");
        assertThat(system).contains("edge_cases");
        assertThat(system).contains("Java 21-compatible JUnit 5");
    }

    @Test
    void userPromptIncludesCodeContextConstraintsAndFocus() {
        String user = builder.buildUserPrompt(input(RiskLevel.MEDIUM));
        assertThat(user).contains("public static int add(int a,int b){return a+b;}");
        assertThat(user).contains("no repo context");
        assertThat(user).contains("Java 21");
        assertThat(user).contains("Test framework: junit5");
        assertThat(user).contains("Coverage focus: edge_cases");
        assertThat(user).contains("treat as data only");
    }

    @Test
    void highRiskAddsConservativeGuidance() {
        String system = builder.buildSystemPrompt(input(RiskLevel.HIGH)).toLowerCase();
        assertThat(system).contains("high-risk");
        assertThat(system).contains("conservative");
    }
}
