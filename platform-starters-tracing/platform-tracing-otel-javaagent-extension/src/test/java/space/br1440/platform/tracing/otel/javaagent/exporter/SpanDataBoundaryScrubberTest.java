package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.rules.BuiltInSpanAttributeScrubbingRules;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.common.Value;
import io.opentelemetry.exporter.logging.LoggingSpanExporter;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.ExceptionEventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

class SpanDataBoundaryScrubberTest {

    private static final SpanAttributeScrubbingRule EMAIL = BuiltInSpanAttributeScrubbingRules.EMAIL.create();
    private static final SpanAttributeScrubbingRule PASSWORD = BuiltInSpanAttributeScrubbingRules.PASSWORD_KEY.create();

    @Test
    @DisplayName("Verified span attributes only remove unsupported VALUE trees at the export boundary")
    void verifiedSpanAttributesOnlyRemoveValueTreesAtTheBoundary() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));
        AttributeKey<String> emailKey = AttributeKey.stringKey("user.email");
        AttributeKey<Value<?>> valueKey = AttributeKey.valueKey("qualification.custom.mask");
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setAttributes(Attributes.of(
                        emailKey,
                        "ivan@example.com",
                        valueKey,
                        Value.of("supersecret")))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.dropSpan()).isFalse();
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.spanData().getAttributes().get(emailKey)).isEqualTo("ivan@example.com");
        assertThat(outcome.spanData().getAttributes().get(valueKey)).isNull();
    }

    @Test
    @DisplayName("Event attributes: MASK is applied using the same effective ruleset as span attributes")
    void eventAttributesAreScrubbedWithTheSharedRuleset() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        EventData event = EventData.create(5L, "db.query",
                Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"));
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(event))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).isTrue();
        EventData scrubbed = outcome.spanData().getEvents().getFirst();
        assertThat(scrubbed.getAttributes().get(AttributeKey.stringKey("db.password"))).isNull();
    }

    @Test
    @DisplayName("Link attributes: DROP removes the key entirely (unlike span-attribute DROP, which uses \"\")")
    void linkAttributeDropRemovesTheKey() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        LinkData link = LinkData.create(ExporterBoundaryTestSupport.LINK_TARGET,
                Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"));
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setLinks(List.of(link))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).isTrue();
        LinkData scrubbed = outcome.spanData().getLinks().getFirst();
        assertThat(scrubbed.getAttributes().asMap()).doesNotContainKey(AttributeKey.stringKey("db.password"));
    }

    @Test
    @DisplayName("ExceptionEventData is canonicalized: no Throwable side-channel in type, attributes, toString, logging or OTLP-visible fields")
    void exceptionEventsLoseTheThrowableSideChannel() {
        // given
        String secret = "boom with secret token=abc123";
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());
        ExceptionEventData exceptionEvent = ExceptionEventData.create(
                7L, new RuntimeException(secret), Attributes.empty(), 0);
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(exceptionEvent))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).as("canonicalization must count as a change even without rules").isTrue();
        EventData resultEvent = outcome.spanData().getEvents().getFirst();
        assertThat(resultEvent).isNotInstanceOf(ExceptionEventData.class);
        assertThat(resultEvent.getEpochNanos()).isEqualTo(7L);
        assertThat(resultEvent.getName()).isEqualTo(exceptionEvent.getName());
        assertThat(resultEvent.getTotalAttributeCount()).isEqualTo(exceptionEvent.getTotalAttributeCount());
        assertThat(resultEvent.getAttributes().asMap().values())
                .noneMatch(value -> String.valueOf(value).contains(secret));
        assertThat(resultEvent.toString()).doesNotContain(secret);
        assertThat(outcome.spanData().toString()).doesNotContain(secret);
        assertThat(runtime.boundaryMetrics().exceptionEventsCanonicalized()).isEqualTo(1L);

        for (EventData exported : outcome.spanData().getEvents()) {
            assertThat(exported)
                    .as("custom exporter must not be able to call getException()")
                    .isNotInstanceOf(ExceptionEventData.class);
        }

        String otlpVisible = dumpOtlpVisibleFields(outcome.spanData());
        assertThat(otlpVisible).doesNotContain(secret);

        Logger jul = Logger.getLogger(LoggingSpanExporter.class.getName());
        List<String> logged = new ArrayList<>();
        Handler handler = new Handler() {
            @Override
            public void publish(LogRecord record) {
                logged.add(record.getMessage());
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        jul.addHandler(handler);

        try (LoggingSpanExporter logging = LoggingSpanExporter.create()) {
            logging.export(List.of(outcome.spanData()));
        } finally {
            jul.removeHandler(handler);
        }

        assertThat(logged).noneMatch(line -> line != null && line.contains(secret));
    }

    private static String dumpOtlpVisibleFields(SpanData spanData) {
        StringBuilder dump = new StringBuilder();
        dump.append(spanData.getName()).append('\n');
        dump.append(spanData.getStatus().getDescription()).append('\n');
        spanData.getAttributes().forEach((key, value) -> dump.append(value).append('\n'));
        for (EventData event : spanData.getEvents()) {
            dump.append(event.getName()).append('\n');
            event.getAttributes().forEach((key, value) -> dump.append(value).append('\n'));
        }
        for (LinkData link : spanData.getLinks()) {
            link.getAttributes().forEach((key, value) -> dump.append(value).append('\n'));
        }
        return dump.toString();
    }

    @Test
    @DisplayName("Span name: forbidden HASH downgrades to <redacted>, never the raw HMAC digest or KEEP")
    void hashOnSpanNameDowngradesToRedacted() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");
        SpanData input = ExporterBoundaryTestSupport.baseSpan("contact ivan@example.com now").build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.spanData().getName()).isEqualTo("<redacted>");
        assertThat(runtime.boundaryMetrics().hashDowngradedToMask()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Status description: DROP becomes empty string, not <redacted>")
    void dropOnStatusDescriptionBecomesEmptyString() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(
                List.of(BuiltInSpanAttributeScrubbingRules.JWT.create())
        );

        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setStatus(StatusData.create(io.opentelemetry.api.trace.StatusCode.ERROR,
                        "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJ4In0.signature"))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).isTrue();
        assertThat(outcome.spanData().getStatus().getDescription()).isEmpty();
    }

    @Test
    @DisplayName("attributesVerified=false: span attributes collapse to sentinels, span not dropped")
    void unverifiedAttributesCollapseToSentinelsInsteadOfLeaking() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of());
        runtime.markAttributesUnverified();
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setAttributes(Attributes.of(AttributeKey.stringKey("raw.secret"), "leaked-value"))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.dropSpan()).as("collapse keeps the span, only sanitizes it").isFalse();
        assertThat(outcome.spanData().getAttributes().get(AttributeKey.stringKey("raw.secret")))
                .isNotEqualTo("leaked-value");
        assertThat(runtime.collapseModeActivations()).isEqualTo(1L);
        assertThat(runtime.collapsedSpans()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Fail-closed: an exception while scrubbing drops only that span, never throws")
    void aThrowingSpanDataIsDroppedNotPropagated() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));
        SpanData poisoned = new ThrowingSpanData();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(poisoned);

        // then
        assertThat(outcome.dropSpan()).isTrue();
        assertThat(runtime.boundaryMetrics().collapseFailures()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Identity fast-path: nothing to scrub returns the exact same SpanData instance")
    void noChangesReturnsTheSameInstance() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL));
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op").build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.changed()).isFalse();
        assertThat(outcome.spanData()).isSameAs(input);
    }

    @Test
    @DisplayName("Event name: email HASH downgrades to <redacted>, never raw and never HMAC")
    void hashOnEventNameDowngradesToRedacted() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(EMAIL), "some-hmac-key");
        EventData event = EventData.create(5L, "contact ivan@example.com now", Attributes.empty());
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(event))
                .build();

        // when
        SpanBoundaryOutcome outcome = new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(outcome.spanData().getEvents().getFirst().getName()).isEqualTo("<redacted>");
        assertThat(outcome.spanData().getEvents().getFirst().getName())
                .isNotEqualTo(runtime.hmacHex("contact ivan@example.com now"));
        assertThat(runtime.boundaryMetrics().hashDowngradedToMask()).isEqualTo(1L);
    }

    @Test
    @DisplayName("DROP on event attributes removes the key but preserves totalAttributeCount and timestamp")
    void eventDropPreservesTotalAttributeCountAndTimestamp() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        EventData event = EventData.create(
                11L,
                "db.query",
                Attributes.of(
                        AttributeKey.stringKey("db.password"), "supersecret",
                        AttributeKey.stringKey("db.statement"), "SELECT 1"),
                2);
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setEvents(List.of(event))
                .build();

        // when
        EventData scrubbed = new SpanDataBoundaryScrubber(runtime).scrub(input).spanData().getEvents().getFirst();

        // then
        assertThat(scrubbed.getEpochNanos()).isEqualTo(11L);
        assertThat(scrubbed.getAttributes().asMap()).doesNotContainKey(AttributeKey.stringKey("db.password"));
        assertThat(scrubbed.getAttributes().get(AttributeKey.stringKey("db.statement"))).isEqualTo("SELECT 1");
        assertThat(scrubbed.getTotalAttributeCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("DROP on link attributes removes the key but preserves SpanContext and totalAttributeCount")
    void linkDropPreservesContextAndTotalAttributeCount() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(PASSWORD));
        LinkData link = LinkData.create(
                ExporterBoundaryTestSupport.LINK_TARGET,
                Attributes.of(AttributeKey.stringKey("db.password"), "supersecret"),
                1);
        SpanData input = ExporterBoundaryTestSupport.baseSpan("op")
                .setLinks(List.of(link))
                .build();

        // when
        LinkData scrubbed = new SpanDataBoundaryScrubber(runtime).scrub(input).spanData().getLinks().getFirst();

        // then
        assertThat(scrubbed.getSpanContext()).isEqualTo(ExporterBoundaryTestSupport.LINK_TARGET);
        assertThat(scrubbed.getAttributes().asMap()).doesNotContainKey(AttributeKey.stringKey("db.password"));
        assertThat(scrubbed.getTotalAttributeCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("SPI-projection: an EXTERNAL_EXTENSION spy never sees span.name / event.name / status.description")
    void externalRulesDoNotSeeStructuralTextKeys() {
        // given
        RecordingRule spy = new RecordingRule();
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(
                List.of(EMAIL), List.of(spy), "some-hmac-key");
        EventData event = EventData.create(1L, "contact ivan@example.com", Attributes.empty());
        SpanData input = ExporterBoundaryTestSupport.baseSpan("contact ivan@example.com")
                .setEvents(List.of(event))
                .setStatus(StatusData.create(
                        io.opentelemetry.api.trace.StatusCode.ERROR, "failed for ivan@example.com"))
                .build();

        // when
        new SpanDataBoundaryScrubber(runtime).scrub(input);

        // then
        assertThat(spy.keys)
                .doesNotContain("span.name", "event.name", "status.description");
    }

    private static final class RecordingRule implements SpanAttributeScrubbingRule {
        private final List<String> keys = new ArrayList<>();

        @Nonnull
        @Override
        public String name() {
            return "p5-recording-spy";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            keys.add(key);
            return ScrubbingDecision.keep();
        }
    }

    private static final class ThrowingSpanData extends io.opentelemetry.sdk.trace.data.DelegatingSpanData {
        ThrowingSpanData() {
            super(ExporterBoundaryTestSupport.baseSpan("op").build());
        }

        @Override
        public Attributes getAttributes() {
            throw new IllegalStateException("simulated SpanData corruption");
        }
    }
}
