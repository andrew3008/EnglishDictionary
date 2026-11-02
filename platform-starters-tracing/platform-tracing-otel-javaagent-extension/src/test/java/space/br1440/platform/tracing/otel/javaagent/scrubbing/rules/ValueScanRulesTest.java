package space.br1440.platform.tracing.otel.javaagent.scrubbing.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.api.spi.ScrubbingAction;
import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

class ValueScanRulesTest {

    private static final String EMAIL = "ivan@example.com";
    private static final String JWT = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.sig";

    @ParameterizedTest
    @ValueSource(strings = {
            "ivan@example.com",
            "ivan@example.com.",
            "Contact ivan@example.com.",
            "Contact <ivan@example.com>, please.",
            "contact ivan@example.co.uk",
            "Request failed\nContact ivan@example.com\nRetry later",
            "user{1}@example.com",
            "o'neil@example.com",
            "//***@example.com",
            "user@example.xn--p1ai"
    })
    void email_hashes_supported_addresses(String value) {
        // given
        SpanAttributeScrubbingRule rule = new EmailRule();

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.HASH);
        assertThat(decision.reason()).isEqualTo("email");
    }

    @ParameterizedTest
    @ValueSource(strings = {"no email here", "ivan@example..com", "иван@пример.рф"})
    void email_keeps_text_without_supported_addresses(String value) {
        // given
        SpanAttributeScrubbingRule rule = new EmailRule();

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void email_keeps_address_with_oversized_domain_label() {
        // given
        SpanAttributeScrubbingRule rule = new EmailRule();
        String value = "ivan@" + "a".repeat(64) + ".com";

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @Test
    void email_handles_long_local_part_without_valid_domain() {
        // given
        SpanAttributeScrubbingRule rule = new EmailRule();
        String value = "a".repeat(ValueScanPolicy.MAX_INPUT_CHARS - 2) + "@x";

        // when
        ScrubbingDecision decision = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> rule.evaluate("message", value));

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            JWT,
            "Bearer " + JWT,
            "https://host/?token=" + JWT + "&mode=test",
            "{\"token\":\"" + JWT + "\"}",
            "eyJx.a.",
            "_eyJx.a.",
            "eyJx.missing-dot; eyJ.invalid. tail eyJvalid.payload."
    })
    void jwt_drops_complete_tokens(String value) {
        // given
        SpanAttributeScrubbingRule rule = new JwtRule();

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.DROP);
        assertThat(decision.reason()).isEqualTo("jwt");
    }

    @ParameterizedTest
    @ValueSource(strings = {"просто текст", "eyJ", "eyJx.a", "eyJ.a.", "eyJx..signature", "eyJx.a!b."})
    void jwt_keeps_text_without_complete_tokens(String value) {
        // given
        SpanAttributeScrubbingRule rule = new JwtRule();

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @ValueSource(ints = {513, 1_024, 4_000})
    void jwt_drops_tokens_with_long_payload_within_input_limit(int payloadLength) {
        // given
        SpanAttributeScrubbingRule rule = new JwtRule();
        String value = "eyJx." + "a".repeat(payloadLength) + ".signature";

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.DROP);
    }

    @ParameterizedTest
    @ValueSource(strings = {"eyJ", "_eyJ", "eyJ."})
    void jwt_handles_repeated_markers_without_complete_tokens(String marker) {
        // given
        SpanAttributeScrubbingRule rule = new JwtRule();
        String value = marker.repeat(ValueScanPolicy.MAX_INPUT_CHARS / marker.length());

        // when
        ScrubbingDecision decision = assertTimeoutPreemptively(
                Duration.ofSeconds(1), () -> rule.evaluate("message", value));

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @ValueSource(ints = {6_000, 12_000})
    void jwt_masks_repeated_markers_beyond_input_limit(int length) {
        // given
        SpanAttributeScrubbingRule rule = new JwtRule();
        String value = "eyJ".repeat(length / 3);

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertInputLimit(decision);
    }

    @ParameterizedTest
    @MethodSource("matchingRules")
    void rules_find_matches_in_each_array_position_and_skip_nulls(SpanAttributeScrubbingRule rule,
                                                                  String secret,
                                                                  ScrubbingAction action) {
        // given
        List<List<String>> values = List.of(
                List.of(secret, "safe", "safe"),
                List.of("safe", secret, "safe"),
                List.of("safe", "safe", secret),
                Arrays.asList(null, secret, null));

        // when
        List<ScrubbingDecision> decisions = values.stream()
                .map(value -> rule.evaluate("messages", value))
                .toList();

        // then
        assertThat(decisions).allSatisfy(decision -> assertThat(decision.action()).isEqualTo(action));
    }

    @ParameterizedTest
    @MethodSource("matchingRules")
    void rules_scan_matching_scalar_at_character_limit(SpanAttributeScrubbingRule rule,
                                                       String secret,
                                                       ScrubbingAction action) {
        // given
        String value = secret + " ".repeat(ValueScanPolicy.MAX_INPUT_CHARS - secret.length());

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertThat(decision.action()).isEqualTo(action);
    }

    @ParameterizedTest
    @MethodSource("rulesWithSecrets")
    void rules_mask_oversized_scalar_even_with_match_at_start(SpanAttributeScrubbingRule rule,
                                                              String secret) {
        // given
        String value = secret + " ".repeat(ValueScanPolicy.MAX_INPUT_CHARS + 1 - secret.length());

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertInputLimit(decision);
    }

    @ParameterizedTest
    @MethodSource("rulesWithSecrets")
    void rules_check_complete_array_budget_before_matching(SpanAttributeScrubbingRule rule,
                                                           String secret) {
        // given
        String neighbor = "a".repeat(ValueScanPolicy.MAX_INPUT_CHARS - secret.length() + 1);
        List<List<String>> values = List.of(List.of(secret, neighbor), List.of(neighbor, secret));

        // when
        List<ScrubbingDecision> decisions = values.stream()
                .map(value -> rule.evaluate("messages", value))
                .toList();

        // then
        assertThat(decisions).allSatisfy(ValueScanRulesTest::assertInputLimit);
    }

    @ParameterizedTest
    @MethodSource("rulesWithSecrets")
    void rules_mask_malformed_array_before_matching(SpanAttributeScrubbingRule rule,
                                                    String secret) {
        // given
        List<Object> value = List.of(secret, 42L);

        // when
        ScrubbingDecision decision = rule.evaluate("messages", value);

        // then
        assertInputLimit(decision);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_keep_scalar_and_array_at_character_limit(SpanAttributeScrubbingRule rule) {
        // given
        String scalar = "a".repeat(ValueScanPolicy.MAX_INPUT_CHARS);
        List<String> array = List.of("a".repeat(2_048), "b".repeat(2_048));

        // when
        ScrubbingDecision scalarDecision = rule.evaluate("message", scalar);
        ScrubbingDecision arrayDecision = rule.evaluate("messages", array);

        // then
        assertThat(scalarDecision.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(arrayDecision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_mask_oversized_scalar_without_secret_markers(SpanAttributeScrubbingRule rule) {
        // given
        String value = "a".repeat(ValueScanPolicy.MAX_INPUT_CHARS + 1);

        // when
        ScrubbingDecision decision = rule.evaluate("message", value);

        // then
        assertInputLimit(decision);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_keep_numeric_scalar(SpanAttributeScrubbingRule rule) {
        // given
        long value = 123L;

        // when
        ScrubbingDecision decision = rule.evaluate("count", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_keep_arrays_at_element_limit(SpanAttributeScrubbingRule rule) {
        // given
        List<String> value = Collections.nCopies(ValueScanPolicy.MAX_ARRAY_ELEMENTS, "safe");

        // when
        ScrubbingDecision decision = rule.evaluate("messages", value);

        // then
        assertThat(decision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_mask_oversized_arrays_including_null_elements(SpanAttributeScrubbingRule rule) {
        // given
        List<String> overLimit = Collections.nCopies(ValueScanPolicy.MAX_ARRAY_ELEMENTS + 1, null);
        List<String> huge = Collections.nCopies(10_000, "safe");

        // when
        ScrubbingDecision overLimitDecision = rule.evaluate("messages", overLimit);
        ScrubbingDecision hugeDecision = rule.evaluate("messages", huge);

        // then
        assertInputLimit(overLimitDecision);
        assertInputLimit(hugeDecision);
    }

    @ParameterizedTest
    @MethodSource("rules")
    void rules_keep_empty_and_null_only_arrays(SpanAttributeScrubbingRule rule) {
        // given
        List<String> empty = List.of();
        List<String> nulls = Arrays.asList(null, null);

        // when
        ScrubbingDecision emptyDecision = rule.evaluate("messages", empty);
        ScrubbingDecision nullsDecision = rule.evaluate("messages", nulls);

        // then
        assertThat(emptyDecision.action()).isEqualTo(ScrubbingAction.KEEP);
        assertThat(nullsDecision.action()).isEqualTo(ScrubbingAction.KEEP);
    }

    private static Stream<SpanAttributeScrubbingRule> rules() {
        return Stream.of(new EmailRule(), new JwtRule());
    }

    private static Stream<Arguments> matchingRules() {
        return Stream.of(
                Arguments.of(new EmailRule(), EMAIL, ScrubbingAction.HASH),
                Arguments.of(new JwtRule(), JWT, ScrubbingAction.DROP)
        );
    }

    private static Stream<Arguments> rulesWithSecrets() {
        return Stream.of(
                Arguments.of(new EmailRule(), EMAIL),
                Arguments.of(new JwtRule(), JWT)
        );
    }

    private static void assertInputLimit(ScrubbingDecision decision) {
        assertThat(decision.action()).isEqualTo(ScrubbingAction.MASK);
        assertThat(decision.reason()).isEqualTo(ValueScanPolicy.INPUT_LIMIT_REASON);
    }
}
