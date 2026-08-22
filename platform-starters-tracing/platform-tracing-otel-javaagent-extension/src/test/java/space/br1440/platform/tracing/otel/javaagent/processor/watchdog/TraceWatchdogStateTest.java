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
        TraceWatchdogState state = new TraceWatchdogState(1L);
        TraceWatchdogState.Tracked tracked = tracked("span-1", mock(ReadWriteSpan.class));

        assertThat(state.addIfAbsent(tracked)).isTrue();

        assertThat(state.size()).isEqualTo(1);
        assertThat(state.isEmpty()).isFalse();
        assertThat(state.contains("span-1")).isTrue();
        assertThat(state.records()).containsExactly(tracked);
    }

    @Test
    void duplicateSpanIdIsRejectedInSingleMode() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        boolean added = state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        assertThat(added).isFalse();
        assertThat(state.size()).isEqualTo(1);
    }

    @Test
    void secondDistinctSpanIdPromotesToMultipleMode() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        boolean added = state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));

        assertThat(added).isTrue();
        assertThat(state.size()).isEqualTo(2);
        assertThat(state.contains("span-1")).isTrue();
        assertThat(state.contains("span-2")).isTrue();
    }

    @Test
    void duplicateSpanIdIsRejectedInMultipleModeToo() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));

        boolean added = state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        assertThat(added).isFalse();
        assertThat(state.size()).isEqualTo(2);
    }

    @Test
    void removeIfPresentRequiresIdentityMatchOnSpanReference() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        ReadWriteSpan realSpan = mock(ReadWriteSpan.class);
        state.addIfAbsent(tracked("span-1", realSpan));

        boolean removedWithWrongIdentity = state.removeIfPresent("span-1", mock(ReadWriteSpan.class));
        assertThat(removedWithWrongIdentity).isFalse();
        assertThat(state.size()).isEqualTo(1);

        boolean removedWithCorrectIdentity = state.removeIfPresent("span-1", realSpan);
        assertThat(removedWithCorrectIdentity).isTrue();
        assertThat(state.isEmpty()).isTrue();
    }

    @Test
    void removeIfPresentInMultipleModeCollapsesBackToSingleWhenOneRemains() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        ReadWriteSpan first = mock(ReadWriteSpan.class);
        ReadWriteSpan second = mock(ReadWriteSpan.class);
        state.addIfAbsent(tracked("span-1", first));
        state.addIfAbsent(tracked("span-2", second));

        boolean removed = state.removeIfPresent("span-1", first);

        assertThat(removed).isTrue();
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.contains("span-2")).isTrue();
        // collapse back to single mode must still reject a duplicate of the remaining span
        assertThat(state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)))).isFalse();
    }

    @Test
    void removeByReferenceCollapsesMultipleBackToSingle() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        TraceWatchdogState.Tracked first = tracked("span-1", mock(ReadWriteSpan.class));
        TraceWatchdogState.Tracked second = tracked("span-2", mock(ReadWriteSpan.class));
        state.addIfAbsent(first);
        state.addIfAbsent(second);

        boolean removed = state.remove(first);

        assertThat(removed).isTrue();
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.records()).containsExactly(second);
    }

    @Test
    void removeUnknownTrackedReturnsFalseAndDoesNotMutateState() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        TraceWatchdogState.Tracked foreign = tracked("span-1", mock(ReadWriteSpan.class));

        // identity-based equals/hashCode: a distinct Tracked instance with the same fields
        // must not be treated as the same tracked record.
        boolean removed = state.remove(foreign);

        assertThat(removed).isFalse();
        assertThat(state.size()).isEqualTo(1);
    }

    @Test
    void drainClearsSingleModeAndReportsOneRemoved() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));

        int drained = state.drain();

        assertThat(drained).isEqualTo(1);
        assertThat(state.isEmpty()).isTrue();
        assertThat(state.records()).isEmpty();
    }

    @Test
    void drainClearsMultipleModeAndReportsExactCount() {
        TraceWatchdogState state = new TraceWatchdogState(1L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-2", mock(ReadWriteSpan.class)));
        state.addIfAbsent(tracked("span-3", mock(ReadWriteSpan.class)));

        int drained = state.drain();

        assertThat(drained).isEqualTo(3);
        assertThat(state.isEmpty()).isTrue();
        assertThat(state.records()).isEmpty();
    }

    @Test
    void drainOnEmptyStateReportsZero() {
        TraceWatchdogState state = new TraceWatchdogState(1L);

        assertThat(state.drain()).isZero();
    }

    @Test
    void startNanosIsImmutableAcrossMutations() {
        TraceWatchdogState state = new TraceWatchdogState(42L);
        state.addIfAbsent(tracked("span-1", mock(ReadWriteSpan.class)));
        state.drain();

        assertThat(state.startNanos()).isEqualTo(42L);
    }

    private static TraceWatchdogState.Tracked tracked(String spanId, ReadWriteSpan span) {
        return new TraceWatchdogState.Tracked(spanId, new WeakReference<>(span), 0L);
    }
}
