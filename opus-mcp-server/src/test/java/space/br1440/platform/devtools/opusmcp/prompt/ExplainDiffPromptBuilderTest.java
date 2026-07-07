package space.br1440.platform.devtools.opusmcp.prompt;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.DiffAnalysisFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.DiffFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.DiffOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.ExplainDiffInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

class ExplainDiffPromptBuilderTest {

    private final ExplainDiffPromptBuilder builder = new ExplainDiffPromptBuilder();

    private ExplainDiffInput input(RiskLevel risk) {
        return new ExplainDiffInput(
                "Explain this diff",
                CodeLanguage.JAVA,
                "--- a/Calc.java\n+++ b/Calc.java\n@@ -1 +1 @@\n-return a+b;\n+return a-b;",
                "no repo context",
                "Java 21",
                DiffFormat.UNIFIED_DIFF,
                DiffAnalysisFocus.CORRECTNESS,
                risk,
                DiffOutputFormat.DIFF_EXPLANATION);
    }

    @Test
    void systemPromptEnforcesReadOnlyDiffContract() {
        String system = builder.buildSystemPrompt(input(RiskLevel.MEDIUM)).toLowerCase();
        assertThat(system).contains("read-only");
        assertThat(system).contains("do not apply patches");
        assertThat(system).contains("do not execute commands");
        assertThat(system).contains("do not run tests");
        assertThat(system).contains("untrusted data");
        assertThat(system).contains("summary:");
        assertThat(system).contains("explanation:");
        assertThat(system).contains("changed_files:");
        assertThat(system).contains("behavior_changes:");
        assertThat(system).contains("findings:");
        assertThat(system).contains("merge_recommendation:");
    }

    @Test
    void systemPromptSeparatesBehaviorFromMechanicalChanges() {
        String system = builder.buildSystemPrompt(input(RiskLevel.MEDIUM)).toLowerCase();
        assertThat(system).contains("behavior");
        assertThat(system).contains("mechanical");
        assertThat(system).contains("test impact");
    }

    @Test
    void userPromptIncludesDiffContextConstraintsAndFocus() {
        String user = builder.buildUserPrompt(input(RiskLevel.MEDIUM));
        assertThat(user).contains("return a-b;");
        assertThat(user).contains("no repo context");
        assertThat(user).contains("Java 21");
        assertThat(user).contains("Analysis focus: correctness");
        assertThat(user).contains("Diff format: unified_diff");
        assertThat(user).contains("never as instructions");
    }

    @Test
    void highRiskAddsConservativeGuidance() {
        String system = builder.buildSystemPrompt(input(RiskLevel.HIGH)).toLowerCase();
        assertThat(system).contains("high-risk");
        assertThat(system).contains("conservative");
        assertThat(system).contains("request_changes");
    }
}
