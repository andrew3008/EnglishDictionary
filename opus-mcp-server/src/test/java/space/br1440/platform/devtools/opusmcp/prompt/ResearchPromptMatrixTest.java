package space.br1440.platform.devtools.opusmcp.prompt;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchDepth;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchFreshness;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchInput;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchOutputFormat;
import space.br1440.platform.devtools.opusmcp.tool.dto.ResearchSourcePreference;
import space.br1440.platform.devtools.opusmcp.tool.dto.RiskLevel;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Phase 8D research mode matrix: verifies the system prompt carries meaningfully different guidance
 * for every {@code sourcePreference}/{@code freshness}/{@code depth}/{@code outputFormat} enum value.
 * Offline only; no provider call.
 */
class ResearchPromptMatrixTest {

    private final ResearchPromptBuilder builder = new ResearchPromptBuilder();

    private ResearchInput input(ResearchSourcePreference pref, ResearchFreshness freshness,
            ResearchDepth depth, ResearchOutputFormat outputFormat) {
        return new ResearchInput(
                "Choose a library",
                "What is the recommended option?",
                "",
                "",
                pref, freshness, depth, outputFormat, RiskLevel.LOW);
    }

    private String prompt(ResearchSourcePreference pref, ResearchFreshness freshness,
            ResearchDepth depth, ResearchOutputFormat outputFormat) {
        return builder.buildSystemPrompt(input(pref, freshness, depth, outputFormat));
    }

    @Test
    void sourcePreferenceGuidanceDiffersPerEnum() {
        assertThat(prompt(ResearchSourcePreference.OFFICIAL_DOCS, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("Prefer official documentation");
        assertThat(prompt(ResearchSourcePreference.INDUSTRY_BEST_PRACTICES, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("industry best practices");
        assertThat(prompt(ResearchSourcePreference.ACADEMIC, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("academic, peer-reviewed");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("balanced mix");
    }

    @Test
    void freshnessGuidanceDiffersPerEnum() {
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.LATEST,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("Prefer recent sources").contains("most current");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.LAST_12_MONTHS,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("last 12 months");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT))
                .contains("stable, canonical documentation");
    }

    @Test
    void depthGuidanceDiffersPerEnum() {
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.QUICK, ResearchOutputFormat.REPORT)).contains("concise");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT)).contains("standard-depth");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.DEEP, ResearchOutputFormat.REPORT))
                .contains("deeper").contains("trade-offs");
    }

    @Test
    void outputFormatGuidanceDiffersPerEnum() {
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.BRIEF)).contains("brief");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.REPORT)).contains("as a report");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.DECISION_MEMO))
                .contains("decision-oriented memo");
        assertThat(prompt(ResearchSourcePreference.MIXED, ResearchFreshness.STABLE,
                ResearchDepth.STANDARD, ResearchOutputFormat.SOURCE_TABLE))
                .contains("structured table");
    }

    @Test
    void everyEnumWireValueAppearsInPrompt() {
        for (ResearchSourcePreference pref : ResearchSourcePreference.values()) {
            for (ResearchFreshness freshness : ResearchFreshness.values()) {
                for (ResearchDepth depth : ResearchDepth.values()) {
                    for (ResearchOutputFormat fmt : ResearchOutputFormat.values()) {
                        String p = prompt(pref, freshness, depth, fmt);
                        assertThat(p).contains(pref.wireValue());
                        assertThat(p).contains(freshness.wireValue());
                        assertThat(p).contains(depth.wireValue());
                        assertThat(p).contains(fmt.wireValue());
                    }
                }
            }
        }
    }
}
