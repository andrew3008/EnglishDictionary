package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ExportOutageTrackerTest {

    @Test
    void lateSuccessStartedBeforeFailureDoesNotCloseTheOutage() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker();
        long first = tracker.startAttempt();
        long failure = tracker.startAttempt();

        // when
        tracker.recordFailure(failure);
        long closedByLateSuccess = tracker.recordSuccess(first);

        // then
        assertThat(closedByLateSuccess).isZero();
        assertThat(tracker.recordSuccess(tracker.startAttempt())).isEqualTo(1L);
    }

    @Test
    void successStartedBeforeTheNewestFailureDoesNotCloseTheOutage() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker();
        long firstFailure = tracker.startAttempt();
        tracker.recordFailure(firstFailure);
        long candidate = tracker.startAttempt();
        long newestFailure = tracker.startAttempt();

        // when
        tracker.recordFailure(newestFailure);
        long closedByCandidate = tracker.recordSuccess(candidate);

        // then
        assertThat(closedByCandidate).isZero();
        assertThat(tracker.recordSuccess(tracker.startAttempt())).isEqualTo(2L);
    }

    @Test
    void lateFailureStartedBeforeCloseDoesNotOpenAnotherOutage() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker();
        long lateFailure = tracker.startAttempt();
        long failure = tracker.startAttempt();
        tracker.recordFailure(failure);
        long success = tracker.startAttempt();

        // when
        long closed = tracker.recordSuccess(success);
        tracker.recordFailure(lateFailure);

        // then
        assertThat(closed).isEqualTo(1L);
        assertThat(tracker.recordSuccess(tracker.startAttempt())).isZero();
    }

    @Test
    void lateFailureAfterANewerSuccessDoesNotOpenAnOutage() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker();
        long lateFailure = tracker.startAttempt();
        long success = tracker.startAttempt();

        // when
        long closed = tracker.recordSuccess(success);
        tracker.recordFailure(lateFailure);

        // then
        assertThat(closed).isZero();
        assertThat(tracker.recordSuccess(tracker.startAttempt())).isZero();
    }

    @Test
    void closedOutageReportsEveryFailureOnce() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker();
        tracker.recordFailure(tracker.startAttempt());
        tracker.recordFailure(tracker.startAttempt());
        tracker.recordFailure(tracker.startAttempt());

        // when
        long closed = tracker.recordSuccess(tracker.startAttempt());
        long later = tracker.recordSuccess(tracker.startAttempt());

        // then
        assertThat(closed).isEqualTo(3L);
        assertThat(later).isZero();
    }

    @Test
    void fencingSurvivesSignedLongRollover() {
        // given
        ExportOutageTracker tracker = new ExportOutageTracker(Long.MAX_VALUE - 1L);
        long beforeRollover = tracker.startAttempt();
        tracker.recordFailure(beforeRollover);
        long afterRollover = tracker.startAttempt();

        // when
        long closed = tracker.recordSuccess(afterRollover);

        // then
        assertThat(beforeRollover).isEqualTo(Long.MAX_VALUE);
        assertThat(afterRollover).isEqualTo(Long.MIN_VALUE);
        assertThat(closed).isEqualTo(1L);
    }
}
