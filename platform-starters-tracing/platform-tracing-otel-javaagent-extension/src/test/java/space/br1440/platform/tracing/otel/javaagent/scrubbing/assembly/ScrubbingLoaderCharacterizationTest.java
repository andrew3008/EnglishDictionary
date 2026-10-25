package space.br1440.platform.tracing.otel.javaagent.scrubbing.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.SanitizerBootstrapException;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntimeTestFixtures;
import space.br1440.platform.tracing.test.assertions.ScrubbingAssertions;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.Tracer;

class ScrubbingLoaderCharacterizationTest {

    @Test
    void loader_missing_resource_fails_closed() {
        // when
        // then
        assertThatThrownBy(() -> ScrubbingRulesLoader.load(
                "classpath:tracing/no-such-scrubbing-rules.properties",
                ScrubbingLoaderCharacterizationTest.class.getClassLoader()
        ))
                .isInstanceOf(SanitizerBootstrapException.class)
                .hasMessageContaining("platform.tracing.scrubbing.rules-config");
    }

    @Test
    void loader_classpath_properties_preserves_order() {
        // when
        List<String> rules = ScrubbingRulesLoader.load(
                "classpath:tracing/scrubbing-rules-test.properties",
                ScrubbingLoaderCharacterizationTest.class.getClassLoader()
        );

        // then
        assertThat(rules).containsExactly("iban", "inn", "snils");
    }

    @Test
    void startup_policy_is_immutable_and_scrubs_password() {
        // given
        ScrubbingRuntime runtime = ScrubbingRuntimeTestFixtures.fromBuiltInRules(
                List.of(BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create()),
                null,
                false
        );

        // then
        assertThat(runtime.enabled()).isTrue();
        assertThat(runtime.policyVersion()).isEqualTo(1L);

        // when
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new ScrubbingSpanProcessor(runtime))) {
            Tracer tracer = h.tracer("t");
            Span span = tracer.spanBuilder("op").startSpan();
            span.setAttribute("db.password", "supersecret");
            span.end();

            // then
            ScrubbingAssertions.assertStringAttributeEmpty(
                    h.exporter().getFinishedSpanItems().getFirst(),
                    "db.password"
            );
        }
    }
}
