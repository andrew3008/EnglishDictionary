package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import io.opentelemetry.sdk.trace.data.SpanData;
import space.br1440.platform.tracing.api.spi.SensitiveDataRule;
import space.br1440.platform.tracing.otel.extension.scrubbing.BuiltInSensitiveDataRules;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.ScrubbingDecisionCase;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/**
 * Выполнение {@link ScrubbingDecisionCase} против текущего {@link ScrubbingSpanProcessor}.
 */
final class ScrubbingCharacterizationSupport {

    private ScrubbingCharacterizationSupport() {
    }

    static void assertScrubbingCase(ScrubbingDecisionCase c) {
        List<SensitiveDataRule> rules = resolveRules(c.ruleNames());
        ScrubbingSpanProcessor processor = c.hmacKey() == null
                ? new ScrubbingSpanProcessor(rules)
                : new ScrubbingSpanProcessor(rules, c.hmacKey(), false);

        try (SpanProcessorHarness harness = SpanProcessorHarness.of(processor)) {
            Tracer tracer = harness.tracer("scrubbing-characterization");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute(c.inputKey(), c.inputValue());
            span.end();

            SpanData data = harness.exporter().getFinishedSpanItems().get(0);
            ScrubbingAssertions.assertExportAlive(data);

            if (c.valuePreservedExactly()) {
                ScrubbingAssertions.assertStringAttributePreserved(data, c.inputKey(), c.inputValue());
            } else if (c.expectedStringValue() == null) {
                ScrubbingAssertions.assertStringAttributeEmpty(data, c.inputKey());
            } else {
                ScrubbingAssertions.assertStringAttribute(data, c.inputKey(), c.expectedStringValue());
            }
        }
    }

    static Stream<ScrubbingDecisionCase> scrubbingMatrix() {
        return Stream.of(
                new ScrubbingDecisionCase(
                        "SCR-PW-01", "db.password", "supersecret",
                        List.of("password"), null, null, false, "drop"),
                new ScrubbingDecisionCase(
                        "SCR-JWT-01", "token.value",
                        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature",
                        List.of("jwt"), null, null, false, "drop"),
                new ScrubbingDecisionCase(
                        "SCR-KEEP-01", "just.text", "nothing sensitive",
                        List.of("password", "jwt"), null, "nothing sensitive", true, "keep"),
                new ScrubbingDecisionCase(
                        "SCR-EMAIL-01", "user.email", "ivan@example.com",
                        List.of("email"), null, "***", false, "mask"),
                new ScrubbingDecisionCase(
                        "SCR-OAUTH-01", "http.request.header.authorization", "Bearer secret",
                        List.of("oauth-header"), null, null, false, "drop"),
                new ScrubbingDecisionCase(
                        "SCR-IP-01", "client.address", "10.1.2.3",
                        List.of("ip-address"), null, "10.1.2.0", false, "truncate")
        );
    }

    static List<SensitiveDataRule> resolveRules(List<String> names) {
        List<SensitiveDataRule> rules = new ArrayList<>();
        for (String name : names) {
            SensitiveDataRule rule = BuiltInSensitiveDataRules.resolve(name);
            if (rule != null) {
                rules.add(rule);
            }
        }
        return rules;
    }
}
