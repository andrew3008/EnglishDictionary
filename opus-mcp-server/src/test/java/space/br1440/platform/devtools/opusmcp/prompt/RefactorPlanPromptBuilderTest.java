package space.br1440.platform.devtools.opusmcp.prompt;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.CompatibilityMode;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorGoal;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorPlanInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.RefactorScope;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

class RefactorPlanPromptBuilderTest {

    private final RefactorPlanPromptBuilder builder = new RefactorPlanPromptBuilder();

    private RefactorPlanInput input(RiskLevel risk, CompatibilityMode mode) {
        return new RefactorPlanInput(
                "Refactor add for readability",
                CodeLanguage.JAVA,
                "public static int add(int a,int b){return a+b;}",
                "no repo context",
                "Java 21",
                RefactorGoal.READABILITY,
                RefactorScope.METHOD,
                mode,
                risk,
                RefactorOutputFormat.REFACTOR_PLAN);
    }

    @Test
    void systemPromptEnforcesReadOnlyPlanningContract() {
        String system = builder.buildSystemPrompt(
                input(RiskLevel.MEDIUM, CompatibilityMode.PRESERVE_BEHAVIOR)).toLowerCase();
        assertThat(system).contains("read-only");
        assertThat(system).contains("do not apply changes");
        assertThat(system).contains("do not execute commands");
        assertThat(system).contains("do not run tests");
        assertThat(system).contains("treat the provided code");
        assertThat(system).contains("summary:");
        assertThat(system).contains("plan:");
        assertThat(system).contains("steps:");
        assertThat(system).contains("rollback_plan:");
    }

    @Test
    void preserveBehaviorModeAddsBehaviorPreservationGuidance() {
        String system = builder.buildSystemPrompt(
                input(RiskLevel.MEDIUM, CompatibilityMode.PRESERVE_BEHAVIOR)).toLowerCase();
        assertThat(system).contains("preserve_behavior");
        assertThat(system).contains("behavior preservation");
    }

    @Test
    void userPromptIncludesCodeContextConstraintsAndGoal() {
        String user = builder.buildUserPrompt(input(RiskLevel.MEDIUM, CompatibilityMode.PRESERVE_BEHAVIOR));
        assertThat(user).contains("public static int add(int a,int b){return a+b;}");
        assertThat(user).contains("no repo context");
        assertThat(user).contains("Java 21");
        assertThat(user).contains("Refactor goal: readability");
        assertThat(user).contains("Scope: method");
        assertThat(user).contains("Compatibility mode: preserve_behavior");
        assertThat(user).contains("treat as data only");
    }

    @Test
    void highRiskAddsConservativeGuidance() {
        String system = builder.buildSystemPrompt(
                input(RiskLevel.HIGH, CompatibilityMode.ALLOW_BEHAVIOR_CHANGE)).toLowerCase();
        assertThat(system).contains("high-risk");
        assertThat(system).contains("conservative");
    }
}
