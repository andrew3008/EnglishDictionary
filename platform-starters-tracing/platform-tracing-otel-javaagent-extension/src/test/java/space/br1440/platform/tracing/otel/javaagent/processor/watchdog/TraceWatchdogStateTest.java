package space.br1440.platform.tracing.otel.javaagent.processor.watchdog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.ref.WeakReference;

import org.junit.jupiter.api.Test;

import io.opentelemetry.sdk.trace.ReadWriteSpan;

/** Прямая характеризация single/multiple promotion-collapse в {@link TraceWatchdogState}. */
class TraceWatchdogStateTest {

    @Test
    void singleTrackedSpanIsReportedWithoutPromotion() {
        // given
        TraceWatchdogState state = new TraceWatchdogState(1L);
        TraceWatchdogState.Tracked tracked = tracked("span-1", mock(ReadWriteSpan.class));

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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        // collapse back to single mode must still reject a duplicate of the remaining span
        assertThat(state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)))).isFalse();
    }

    @Test
    void removeByReferenceCollapsesMultipleBackToSingle() {
        // given
        TraceWatchdogState state = new TraceWatchdogState(1L);
        TraceWatchdogState.Tracked first = tracked("span-1", mock(ReadWriteSpan.class));
        TraceWatchdogState.Tracked second = tracked("span-2", mock(ReadWriteSpan.class));
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        TraceWatchdogState.Tracked foreign = tracked("span-1", mock(ReadWriteSpan.class));

        // when
        // identity-based equals/hashCode: a distinct Tracked instance with the same fields
        // must not be treated as the same tracked record.
        boolean removed = state.remove(foreign);

        // then
        assertThat(removed).isFalse();
        assertThat(state.size()).isEqualTo(1);
    }

    @Test
    void drainClearsSingleModeAndReportsOneRemoved() {
        // given
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);
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
        TraceWatchdogState state = new TraceWatchdogState(1L);

        // when/then
        assertThat(state.drain()).isZero();
    }

    @Test
    void startNanosIsImmutableAcrossMutations() {
        // given/when
        TraceWatchdogState state = new TraceWatchdogState(42L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.drain();

        // then
        assertThat(state.startNanos()).isEqualTo(42L);
    }

    private static TraceWatchdogState.Tracked tracked(String spanId, ReadWriteSpan span) {
        return new TraceWatchdogState.Tracked(spanId, new WeakReference<>(span), 0L);
    }
}
