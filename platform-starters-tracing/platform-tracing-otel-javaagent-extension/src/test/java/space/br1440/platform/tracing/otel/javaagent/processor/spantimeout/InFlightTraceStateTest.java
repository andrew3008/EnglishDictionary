package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

/** Прямая характеризация single/multiple promotion-collapse в {@link InFlightTraceState}. */
class InFlightTraceStateTest {

    @Test
    void singleTrackedSpanIsReportedWithoutPromotion() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        InFlightTraceState.Tracked tracked = tracked("span-1", mock(ReadWriteSpan.class));

        // when
        assertThat(state.addIfAbsent(tracked)).isTrue();

        // then
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.isEmpty()).isFalse();
        assertThat(state.contains("span-1")).isTrue();
        assertThat(state.records()).containsExactly(tracked);
    }

    @Test
    void duplicateSpanIdIsRejectedInSingleMode() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        // when
        boolean added = state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        // then
        assertThat(added).isFalse();
        assertThat(state.size()).isEqualTo(1);
    }

    @Test
    void secondDistinctSpanIdPromotesToMultipleMode() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        // when
        boolean added = state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));

        // then
        assertThat(added).isTrue();
        assertThat(state.size()).isEqualTo(2);
        assertThat(state.contains("span-1")).isTrue();
        assertThat(state.contains("span-2")).isTrue();
    }

    @Test
    void duplicateSpanIdIsRejectedInMultipleModeToo() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));

        // when
        boolean added = state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        // then
        assertThat(added).isFalse();
        assertThat(state.size()).isEqualTo(2);
    }

    @Test
    void removeIfPresentRequiresIdentityMatchOnSpanReference() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        ReadWriteSpan realSpan = mock(ReadWriteSpan.class);
        state.addIfAbsent(tracked("span-1", realSpan));

        // when
        boolean removedWithWrongIdentity = state.removeIfPresent("span-1", mock(ReadWriteSpan.class));
        // then
        assertThat(removedWithWrongIdentity).isFalse();
        assertThat(state.size()).isEqualTo(1);

        // when
        boolean removedWithCorrectIdentity = state.removeIfPresent("span-1", realSpan);
        // then
        assertThat(removedWithCorrectIdentity).isTrue();
        assertThat(state.isEmpty()).isTrue();
    }

    @Test
    void removeIfPresentInMultipleModeCollapsesBackToSingleWhenOneRemains() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        ReadWriteSpan first = mock(ReadWriteSpan.class);
        ReadWriteSpan second = mock(ReadWriteSpan.class);
        state.addIfAbsent(tracked("span-1", first));
        state.addIfAbsent(tracked("span-2", second));

        // when
        boolean removed = state.removeIfPresent("span-1", first);

        // then
        assertThat(removed).isTrue();
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.contains("span-2")).isTrue();
        assertThat(state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)))).isFalse();
    }

    @Test
    void removeByReferenceCollapsesMultipleBackToSingle() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        InFlightTraceState.Tracked first = tracked("span-1", mock(ReadWriteSpan.class));
        InFlightTraceState.Tracked second = tracked("span-2", mock(ReadWriteSpan.class));
        state.addIfAbsent(first);
        state.addIfAbsent(second);

        // when
        boolean removed = state.remove(first);

        // then
        assertThat(removed).isTrue();
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.records()).containsExactly(second);
    }

    @Test
    void removeUnknownTrackedReturnsFalseAndDoesNotMutateState() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        InFlightTraceState.Tracked foreign = tracked("span-1", mock(ReadWriteSpan.class));

        // when
        boolean removed = state.remove(foreign);

        // then
        assertThat(removed).isFalse();
        assertThat(state.size()).isEqualTo(1);
    }

    @Test
    void drainClearsSingleModeAndReportsOneRemoved() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        // when
        int drained = state.drain();

        // then
        assertThat(drained).isEqualTo(1);
        assertThat(state.isEmpty()).isTrue();
        assertThat(state.records()).isEmpty();
    }

    @Test
    void drainClearsMultipleModeAndReportsExactCount() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-3", mock(ReadWriteSpan.class)));

        // when
        int drained = state.drain();

        // then
        assertThat(drained).isEqualTo(3);
        assertThat(state.isEmpty()).isTrue();
        assertThat(state.records()).isEmpty();
    }

    @Test
    void drainOnEmptyStateReportsZero() {
        // given
        InFlightTraceState state = new InFlightTraceState(1L);

        // when/then
        assertThat(state.drain()).isZero();
    }

    @Test
    void startNanosIsImmutableAcrossMutations() {
        // given/when
        InFlightTraceState state = new InFlightTraceState(42L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.drain();

        // then
        assertThat(state.startNanos()).isEqualTo(42L);
    }

    private static InFlightTraceState.Tracked tracked(String spanId, ReadWriteSpan span) {
        return new InFlightTraceState.Tracked(spanId, span, 0L);
    }
}
