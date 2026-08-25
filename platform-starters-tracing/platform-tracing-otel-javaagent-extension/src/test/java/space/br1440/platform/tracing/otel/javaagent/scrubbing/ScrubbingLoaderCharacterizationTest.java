package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Characterization loader + immutable startup Category B scrubbing policy.
 */
class ScrubbingLoaderCharacterizationTest {

    @Test
    void loader_missing_resource_returns_empty_without_throw() {
        List<String> rules = ScrubbingRulesLoader.load(
                "classpath:tracing/no-such-scrubbing-rules.properties",
                ScrubbingLoaderCharacterizationTest.class.getClassLoader());
        assertThat(rules).isEmpty();
    }

    @Test
    void loader_classpath_properties_preserves_order() {
        List<String> rules = ScrubbingRulesLoader.load(
                "classpath:tracing/scrubbing-rules-test.properties",
                ScrubbingLoaderCharacterizationTest.class.getClassLoader());
        assertThat(rules).containsExactly("iban", "inn", "snils");
    }

    @Test
    void startup_policy_is_immutable_and_scrubs_password() {
        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.resolve("password")), null, false);
        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.policyVersion()).isEqualTo(1L);
        assertThat(runtime.policySource()).isEqualTo("startup");

        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "supersecret");
            span.end();

            ScrubbingAssertions.assertStringAttributeEmpty(
                    h.exporter().getFinishedSpanItems().get(0), "db.password");
        }
    }
}
