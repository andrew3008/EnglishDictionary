package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.circuitbreaker.RuleCircuitBreaker;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.ExecutableRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeEngine;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.engine.MergeOutcome;

class UrlCredentialMaskingRuleTest {

    @Test
    void registeredByDefaultAsCriticalBeforeOtherRules() {
        // given
        SpanAttributeScrubbingRule rule = BuiltInSpanAttributeScrubbingRules.resolve("url-credentials");

        // when
        List<String> defaults = BuiltInSpanAttributeScrubbingRules.defaultConfigNames();

        // then
        assertThat(rule).isInstanceOf(UrlCredentialMaskingRule.class);
        assertThat(rule.critical()).isTrue();
        assertThat(rule.priority()).isLessThan(BuiltInSpanAttributeScrubbingRules.OAUTH_HEADER.priority());
        assertThat(defaults).contains("url-credentials");
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception.message", "status.description"})
    void masksUrlCredentialsOnBothTextSurfaces(String key) {
        // given
        String input = "Failed https://user:password@service.example/path?token=secret#opaque-fragment-canary";
        ExecutableRule rule = executable(new UrlCredentialMaskingRule());

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(List.of(rule), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(outcome.maskedValue()).isEqualTo("Failed https://***@service.example/path?token=***#***");
        assertThat(outcome.criticalFailures()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception.message", "status.description"})
    void keepsTextWithoutCredentials(String key) {
        // given
        String input = "Connection refused";

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(outcome.maskedValue()).isNull();
        assertThat(outcome.criticalFailures()).isZero();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception.message", "status.description"})
    void dropsAmbiguousAuthorityOnBothTextSurfaces(String key) {
        // given
        String input = "Failed https://user:secret";

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.decision().terminal()).isTrue();
        assertThat(outcome.maskedValue()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception.message", "status.description"})
    void dropsOversizedInputWithoutScanningItsPrefix(String key) {
        // given
        String input = "https://service.example/?token=" + "s".repeat(4096);

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.decision().terminal()).isTrue();
        assertThat(outcome.maskedValue()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"url.full", "url.query", "span.name", "event.name", "exception.stacktrace", "custom"})
    void excludesOtherSurfaces(String key) {
        // given
        String input = "https://user:secret@service.example/?token=secret";

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(outcome.maskedValue()).isNull();
    }

    @Test
    void dropsDenseQueryWhenMaskExpansionExceedsBudget() {
        // given
        String input = "https://service.example/?" + "p=x&".repeat(800);

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), "exception.message", input);

        // then
        assertThat(input).hasSizeLessThan(4096);
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.maskedValue()).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"exception.message", "status.description"})
    void dropsStringArraysOnStringOnlySurfaces(String key) {
        // given
        List<String> input = List.of("https://user:secret@service.example/?token=secret");

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), key, input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.decision().terminal()).isTrue();
        assertThat(outcome.maskedValue()).isNull();
    }

    @Test
    void keepsStringArraysOnExcludedSurfaces() {
        // given
        List<String> input = List.of("https://user:secret@service.example/?token=secret");

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule())), "url.full", input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(outcome.maskedValue()).isNull();
    }

    @Test
    void continuesSecretChecksOutsideMaskedUrls() {
        // given
        String input = "https://service.example/?token=secret eyJhbGciOiJIUzI1NiJ9.e30.signature";

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule()), executable(new JwtRule())),
                "exception.message", input);

        // then
        assertThat(outcome.maskedValue()).doesNotContain("token=secret");
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(outcome.decision().reason()).isEqualTo("jwt");
    }

    @Test
    void removesUrlUserinfoBeforeEmailDetection() {
        // given
        String input = "Failed https://user:secret@service.example/path?token=secret";

        // when
        MergeOutcome outcome = MergeEngine.evaluateDetailed(
                List.of(executable(new UrlCredentialMaskingRule()), executable(new EmailRule())),
                "exception.message", input);

        // then
        assertThat(outcome.decision().action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(outcome.maskedValue()).isEqualTo("Failed https://***@service.example/path?token=***");
    }

    private static ExecutableRule executable(SpanAttributeScrubbingRule rule) {
        return new ExecutableRule(rule, new RuleCircuitBreaker(rule.name()));
    }
}
