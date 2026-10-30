package space.br1440.platform.tracing.otel.javaagent.exporter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;

class ExportOutcomeTallyTest {

    @Test
    void everyMutationKeepsTheFiveCountersAndRemainderEqualToTheBatch() {
        // given
        ExportOutcomeTally tally = new ExportOutcomeTally(6);
        assertAccounted(tally, 6);

        // when
        tally.fold(observation(2, ExportResponseObservation.Outcome.NOT_OBSERVED, 0));
        assertAccounted(tally, 6);
        tally.fold(observation(2, ExportResponseObservation.Outcome.PARTIALLY_REJECTED, 1));
        assertAccounted(tally, 6);
        tally.dropOversized();
        assertAccounted(tally, 6);
        tally.fold(observation(1, ExportResponseObservation.Outcome.NOT_DETERMINED, 0));
        assertAccounted(tally, 6);
        tally.dropRemainingAsTransportFailure();

        // then
        assertAccounted(tally, 6);
        assertThat(tally.remaining()).isZero();
        assertThat(tally.toAggregate())
                .isEqualTo(new ExportResponseObservation.AggregateResult(3, 1, 0, 1, 1));
    }

    @Test
    void transportFailureConsumesOnlyTheUnaccountedRemainder() {
        // given
        ExportOutcomeTally tally = new ExportOutcomeTally(4);
        tally.fold(observation(1, ExportResponseObservation.Outcome.ALL_ACCEPTED, 0));

        // when
        tally.dropRemainingAsTransportFailure();
        tally.dropRemainingAsTransportFailure();

        // then
        assertAccounted(tally, 4);
        assertThat(tally.transportDropped()).isEqualTo(3);
        assertThat(tally.toAggregate())
                .isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 3, 0, 0));
    }

    @Test
    void negativeTotalIsRejected() {
        assertThatThrownBy(() -> new ExportOutcomeTally(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("total must not be negative: -1");
    }

    private static void assertAccounted(ExportOutcomeTally tally, int total) {
        assertThat(tally.toAggregate().total() + tally.remaining()).isEqualTo(total);
    }

    private static ExportResponseObservation observation(int count,
                                                         ExportResponseObservation.Outcome outcome,
                                                         long rejected) {
        ExportResponseObservation observation = new ExportResponseObservation(count);
        if (outcome == ExportResponseObservation.Outcome.ALL_ACCEPTED) {
            observation.recordAllAccepted();
        } else if (outcome == ExportResponseObservation.Outcome.PARTIALLY_REJECTED) {
            observation.recordRejected(rejected);
        } else if (outcome == ExportResponseObservation.Outcome.NOT_DETERMINED) {
            observation.recordNotDetermined();
        }
        return observation;
    }
}
