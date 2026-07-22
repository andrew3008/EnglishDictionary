package space.br1440.platform.tracing.otel.extension.processor;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.api.trace.Tracer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.test.assertions.EnrichmentAssertions;
import space.br1440.platform.tracing.test.harness.EnrichmentDecisionCase;
import space.br1440.platform.tracing.test.harness.SpanProcessorHarness;

import java.util.stream.Stream;

/**
 * Characterization enrichment policy matrix (PR-5B).
 */
class EnrichmentPolicyCharacterizationTest {

    static Stream<EnrichmentDecisionCase> characterizedCases() {
        return Stream.of(
                new EnrichmentDecisionCase(
                        "ENR-SERVER-OK", SpanKind.SERVER, StatusCode.OK,
                        null, null, null, "http_server", "success", null),
                new EnrichmentDecisionCase(
                        "ENR-CLIENT-ERR", SpanKind.CLIENT, StatusCode.ERROR,
                        null, null, "billing-service", "http_client", "failure", "billing-service"),
                new EnrichmentDecisionCase(
                        "ENR-DB-OVERRIDE", SpanKind.CLIENT, StatusCode.OK,
                        null, "postgresql", null, "database", "success", null),
                new EnrichmentDecisionCase(
                        "ENR-PRESERVE-TYPE", SpanKind.CLIENT, StatusCode.OK,
                        "rpc", "postgresql", null, "rpc", "success", null)
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("characterizedCases")
    void matrix_case(EnrichmentDecisionCase c) {
        try (SpanProcessorHarness h = SpanProcessorHarness.of(new EnrichingSpanProcessor())) {
            Tracer tracer = h.tracer("enrichment-characterization");
            Span span = tracer.spanBuilder("op").setSpanKind(c.spanKind()).startSpan();
            if (c.presetPlatformType() != null) {
                span.setAttribute(PlatformAttributes.PLATFORM_TYPE, c.presetPlatformType());
            }
            if (c.dbSystemAttribute() != null) {
                span.setAttribute("db.system.name", c.dbSystemAttribute());
            }
            if (c.peerService() != null) {
                span.setAttribute("peer.service", c.peerService());
            }
            if (c.statusCode() == StatusCode.ERROR) {
                span.setStatus(StatusCode.ERROR, "boom");
            }
            span.end();

            var data = h.exporter().getFinishedSpanItems().get(0);
            EnrichmentAssertions.assertPlatformType(data, c.expectedPlatformType());
            EnrichmentAssertions.assertPlatformResult(data, c.expectedPlatformResult());
            if (c.expectedRemoteService() == null) {
                EnrichmentAssertions.assertRemoteServiceAbsent(data);
            } else {
                EnrichmentAssertions.assertRemoteService(data, c.expectedRemoteService());
            }
        }
    }
}
