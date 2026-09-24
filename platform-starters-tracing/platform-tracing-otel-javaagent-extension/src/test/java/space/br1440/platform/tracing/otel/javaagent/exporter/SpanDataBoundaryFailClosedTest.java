package space.br1440.platform.tracing.otel.javaagent.exporter;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import jakarta.annotation.Nonnull;
import jakarta.annotation.Nullable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.spi.ScrubbingDecision;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import io.opentelemetry.api.trace.SpanContext;
import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;
import io.opentelemetry.sdk.common.InstrumentationScopeInfo;
import io.opentelemetry.sdk.resources.Resource;
import io.opentelemetry.sdk.trace.data.EventData;
import io.opentelemetry.sdk.trace.data.LinkData;
import io.opentelemetry.sdk.trace.data.SpanData;
import io.opentelemetry.sdk.trace.data.StatusData;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Нормативная таблица ошибок ADR-scrubbing-hybrid-processor-export-boundary.md.
 * Critical rule failure и infrastructure failure обязаны схлопывать <b>весь</b> слой boundary,
 * а не отдельное значение; span удаляется только тогда, когда сам collapse завершился ошибкой.
 */
class SpanDataBoundaryFailClosedTest {

    private static final AttributeKey<String> EVENT_ATTR = AttributeKey.stringKey("payload");
    private static final AttributeKey<String> LINK_ATTR = AttributeKey.stringKey("link.payload");
    private static final AttributeKey<String> SPAN_ATTR = AttributeKey.stringKey("span.payload");

    private static final SpanAttributeScrubbingRule CRITICAL_THROWING = new SpanAttributeScrubbingRule() {
        @Nonnull
        @Override
        public String name() {
            return "critical-throwing";
        }

        @Override
        public boolean critical() {
            return true;
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            throw new IllegalStateException("rule blew up");
        }
    };

    private static final SpanAttributeScrubbingRule NON_CRITICAL_THROWING = new SpanAttributeScrubbingRule() {
        @Nonnull
        @Override
        public String name() {
            return "non-critical-throwing";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            throw new IllegalStateException("rule blew up");
        }
    };

    private static final SpanAttributeScrubbingRule INERT = new SpanAttributeScrubbingRule() {
        @Nonnull
        @Override
        public String name() {
            return "inert";
        }

        @Nonnull
        @Override
        public ScrubbingDecision evaluate(@Nonnull String key, @Nullable Object value) {
            return ScrubbingDecision.keep();
        }
    };

    @Test
    @DisplayName("Critical rule failure collapses every boundary surface, not just the failing value")
    void criticalFailureCollapsesTheWholeBoundaryLayer() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(CRITICAL_THROWING));
        SpanDataBoundaryScrubber scrubber = new SpanDataBoundaryScrubber(runtime);

        // when
        SpanBoundaryOutcome outcome = scrubber.scrub(richSpan());

        // then
        assertThat(outcome.dropSpan()).isFalse();
        assertThat(outcome.changed()).isTrue();

        SpanData result = outcome.spanData();
        assertThat(result.getName()).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(result.getEvents().getFirst().getName()).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(result.getEvents().getFirst().getAttributes().get(EVENT_ATTR))
                .isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);
        assertThat(result.getLinks().getFirst().getAttributes().get(LINK_ATTR))
                .isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);
        assertThat(result.getStatus().getDescription()).isEqualTo(BoundaryTextPolicy.REDACTED);

        assertThat(result.getAttributes().get(SPAN_ATTR))
                .as("a critical boundary failure must not preserve any raw span surface")
                .isEqualTo(ScrubbingDecision.SCRUBBING_FAILED_REASON);

        assertThat(runtime.boundaryMetrics().criticalFailures()).isPositive();
        assertThat(runtime.boundaryMetrics().surfacesCollapsedFailClosed()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed()).isZero();
    }

    @Test
    @DisplayName("Non-critical rule failure is counted at the boundary and leaves other surfaces intact")
    void nonCriticalFailureDoesNotCollapseTheLayer() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(NON_CRITICAL_THROWING));
        SpanDataBoundaryScrubber scrubber = new SpanDataBoundaryScrubber(runtime);

        // when
        SpanBoundaryOutcome outcome = scrubber.scrub(richSpan());

        // then
        assertThat(outcome.dropSpan()).isFalse();
        assertThat(outcome.spanData().getName()).isEqualTo("op");
        assertThat(runtime.boundaryMetrics().nonCriticalFailures()).isPositive();
        assertThat(runtime.boundaryMetrics().criticalFailures()).isZero();
        assertThat(runtime.boundaryMetrics().surfacesCollapsedFailClosed()).isZero();
    }

    @Test
    @DisplayName("Infrastructure failure collapses the boundary first; the span is still exported")
    void infrastructureFailureCollapsesBeforeDropping() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(INERT));
        SpanDataBoundaryScrubber scrubber = new SpanDataBoundaryScrubber(runtime);

        // when
        SpanBoundaryOutcome outcome = scrubber.scrub(new FlakySpanData(richSpan(), 1));

        // then
        assertThat(outcome.dropSpan()).isFalse();
        assertThat(outcome.spanData().getName()).isEqualTo(BoundaryTextPolicy.REDACTED);
        assertThat(runtime.boundaryMetrics().collapseFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().surfacesCollapsedFailClosed()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed()).isZero();
        assertThat(runtime.boundaryMetrics().outputSpans()).isEqualTo(1L);
    }

    @Test
    @DisplayName("Only a failing collapse drops the span, and the drop is counted")
    void failingCollapseDropsTheSpanAndIncrementsTheCounter() {
        // given
        ScrubbingRuntime runtime = ExporterBoundaryTestSupport.runtimeOf(List.of(INERT));
        SpanDataBoundaryScrubber scrubber = new SpanDataBoundaryScrubber(runtime);

        // when
        SpanBoundaryOutcome outcome = scrubber.scrub(new FlakySpanData(richSpan(), Integer.MAX_VALUE));

        // then
        assertThat(outcome.dropSpan()).isTrue();
        assertThat(runtime.boundaryMetrics().collapseFailures()).isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().spansDroppedFailClosed())
                .as("a dropped span must be visible in boundary.spans_dropped_fail_closed")
                .isEqualTo(1L);
        assertThat(runtime.boundaryMetrics().surfacesCollapsedFailClosed())
                .as("a failed collapse must not be counted as a successful surface collapse")
                .isZero();
        assertThat(runtime.boundaryMetrics().outputSpans()).isZero();
    }

    private static SpanData richSpan() {
        return ExporterBoundaryTestSupport.baseSpan("op")
                .setStatus(StatusData.create(StatusCode.ERROR, "failure detail"))
                .setAttributes(Attributes.of(SPAN_ATTR, "raw-span-value"))
                .setEvents(List.of(EventData.create(1L, "event.name", Attributes.of(EVENT_ATTR, "raw-event-value"))))
                .setLinks(List.of(LinkData.create(
                        ExporterBoundaryTestSupport.LINK_TARGET, Attributes.of(LINK_ATTR, "raw-link-value"))))
                .build();
    }

    /** {@code getLinks()} падает первые {@code failures} вызовов — имитация infrastructure failure. */
    private static final class FlakySpanData implements SpanData {

        private final SpanData delegate;
        private final int failures;
        private final AtomicInteger calls = new AtomicInteger();

        private FlakySpanData(SpanData delegate, int failures) {
            this.delegate = delegate;
            this.failures = failures;
        }

        @Override
        public List<LinkData> getLinks() {
            if (calls.incrementAndGet() <= failures) {
                throw new IllegalStateException("simulated boundary infrastructure failure");
            }
            return delegate.getLinks();
        }

        @Override public String getName() { return delegate.getName(); }
        @Override public SpanKind getKind() { return delegate.getKind(); }
        @Override public SpanContext getSpanContext() { return delegate.getSpanContext(); }
        @Override public SpanContext getParentSpanContext() { return delegate.getParentSpanContext(); }
        @Override public StatusData getStatus() { return delegate.getStatus(); }
        @Override public long getStartEpochNanos() { return delegate.getStartEpochNanos(); }
        @Override public Attributes getAttributes() { return delegate.getAttributes(); }
        @Override public List<EventData> getEvents() { return delegate.getEvents(); }
        @Override public long getEndEpochNanos() { return delegate.getEndEpochNanos(); }
        @Override public boolean hasEnded() { return delegate.hasEnded(); }
        @Override public int getTotalRecordedEvents() { return delegate.getTotalRecordedEvents(); }
        @Override public int getTotalRecordedLinks() { return delegate.getTotalRecordedLinks(); }
        @Override public int getTotalAttributeCount() { return delegate.getTotalAttributeCount(); }
        @Override public InstrumentationScopeInfo getInstrumentationScopeInfo() {
            return delegate.getInstrumentationScopeInfo();
        }
        @Override public Resource getResource() { return delegate.getResource(); }
        @Override
        @SuppressWarnings("deprecation")
        public io.opentelemetry.sdk.common.InstrumentationLibraryInfo getInstrumentationLibraryInfo() {
            return delegate.getInstrumentationLibraryInfo();
        }
    }
}
