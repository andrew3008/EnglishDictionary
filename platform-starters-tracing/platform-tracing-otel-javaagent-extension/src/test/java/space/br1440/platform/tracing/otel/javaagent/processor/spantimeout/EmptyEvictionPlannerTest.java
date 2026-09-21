package space.br1440.platform.tracing.otel.javaagent.processor.spantimeout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.EmptyEvictionPlanner.EvictionCandidate;

class EmptyEvictionPlannerTest {

    @Test
    void selectsAtMostBatchOldestEmptyStates() {
        // given
        List<Map.Entry<String, InFlightTraceState>> entries = List.of(
                entry("t-50", empty(50)),
                entry("t-10", empty(10)),
                entry("t-40", empty(40)),
                entry("t-20", empty(20)),
                entry("t-30", empty(30)));

        // when
        List<EvictionCandidate> plan = EmptyEvictionPlanner.selectOldestEmpty(entries, 3);

        // then
        assertThat(plan).extracting(EvictionCandidate::traceId).containsExactlyInAnyOrder("t-10", "t-20", "t-30");
    }

    @Test
    void skipsNonEmptyStatesRegardlessOfAge() {
        // given
        InFlightTraceState live = new InFlightTraceState(1);
        live.addIfAbsent(new InFlightTraceState.Tracked("s1", SpanTimeoutTestSpans.span(1, 1), 1));
        List<Map.Entry<String, InFlightTraceState>> entries = List.of(
                entry("live", live),
                entry("empty", empty(100)));

        // when
        List<EvictionCandidate> plan = EmptyEvictionPlanner.selectOldestEmpty(entries, 10);

        // then
        assertThat(plan).extracting(EvictionCandidate::traceId).containsExactly("empty");
    }

    @Test
    void tieOnStartNanosIsBrokenByTraceIdDeterministically() {
        // given
        List<Map.Entry<String, InFlightTraceState>> entries = List.of(
                entry("c", empty(5)),
                entry("a", empty(5)),
                entry("b", empty(5)));

        // when
        List<EvictionCandidate> plan = EmptyEvictionPlanner.selectOldestEmpty(entries, 2);

        // then
        assertThat(plan).extracting(EvictionCandidate::traceId).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void candidateCarriesTheExactStateInstanceSeenAtPlanningTime() {
        // given
        InFlightTraceState state = empty(7);

        // when
        List<EvictionCandidate> plan = EmptyEvictionPlanner.selectOldestEmpty(List.of(entry("t", state)), 1);

        // then
        assertThat(plan).singleElement().satisfies(candidate -> assertThat(candidate.expectedState()).isSameAs(state));
    }

    @Test
    void returnsEverythingWhenBatchExceedsCandidateCountAndEmptyListForNoCandidates() {
        // when
        List<EvictionCandidate> all = EmptyEvictionPlanner.selectOldestEmpty(
                List.of(entry("x", empty(1)), entry("y", empty(2))), 100);
        List<EvictionCandidate> none = EmptyEvictionPlanner.selectOldestEmpty(new ArrayList<>(), 100);

        // then
        assertThat(all).hasSize(2);
        assertThat(none).isEmpty();
    }

    @Test
    void rejectsNonPositiveBatch() {
        // when/then
        assertThatThrownBy(() -> EmptyEvictionPlanner.selectOldestEmpty(List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("batch must be greater than zero");
    }

    private static InFlightTraceState empty(long startNanos) {
        return new InFlightTraceState(startNanos);
    }

    private static Map.Entry<String, InFlightTraceState> entry(String traceId, InFlightTraceState state) {
        return Map.entry(traceId, state);
    }
}
