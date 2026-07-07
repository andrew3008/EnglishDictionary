package space.br1440.platform.devtools.opusmcp.prompt;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.CodeLanguage;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewCodeInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewFocus;
import space.br1440.platform.devtools.opusmcp.tool.dto.ReviewOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

class ReviewPromptBuilderTest {

    private final ReviewPromptBuilder builder = new ReviewPromptBuilder();

    private ReviewCodeInput input(ReviewFocus focus, RiskLevel risk) {
        return new ReviewCodeInput(
                "Review for bugs",
                CodeLanguage.JAVA,
                "public int add(int a,int b){return a+b;}",
                "no repo context",
                "Java 21",
                focus,
                risk,
                ReviewOutputFormat.STRUCTURED_REVIEW);
    }

    @Test
    void systemPromptEnforcesReadOnlyReviewContract() {
        String p = builder.buildSystemPrompt(input(ReviewFocus.SECURITY, RiskLevel.LOW));
        assertThat(p).contains("read-only");
        assertThat(p).contains("Do not apply patches");
        assertThat(p).contains("Do not execute commands");
        assertThat(p).contains("untrusted data");
        assertThat(p).contains("SUMMARY:");
        assertThat(p).contains("REVIEW:");
        assertThat(p).contains("FINDINGS:");
        assertThat(p).contains("security");
    }

    @Test
    void userPromptIncludesCodeContextConstraintsAndFocus() {
        String p = builder.buildUserPrompt(input(ReviewFocus.PERFORMANCE, RiskLevel.MEDIUM));
        assertThat(p).contains("public int add(int a,int b){return a+b;}");
        assertThat(p).contains("no repo context");
        assertThat(p).contains("Java 21");
        assertThat(p).contains("performance");
    }

    @Test
    void highRiskAddsConservativeGuidance() {
        String p = builder.buildSystemPrompt(input(ReviewFocus.ALL, RiskLevel.HIGH));
        assertThat(p.toLowerCase()).contains("high-risk");
        assertThat(p.toLowerCase()).contains("blockers");
    }
}
