package space.br1440.platform.tracing.otel.javaagent.exporter.guard;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.exporter.response.ExportResponseObservation;

class SpanOutcomeAccumulatorTest {

    @Test
    void everyMutationKeepsTheSixCountersEqualToTheBatch() {
        // given
        SpanOutcomeAccumulator outcomes = new SpanOutcomeAccumulator();
        IllegalStateException cause = new IllegalStateException("Simulated measurement failure");
        assertAccounted(outcomes, 0);

        // when
        outcomes.fold(observation(2, ExportResponseObservation.Outcome.NOT_OBSERVED, 0));
        assertAccounted(outcomes, 2);
        outcomes.fold(observation(2, ExportResponseObservation.Outcome.PARTIALLY_REJECTED, 1));
        assertAccounted(outcomes, 4);
        outcomes.dropOversized();
        assertAccounted(outcomes, 5);
        outcomes.fold(observation(1, ExportResponseObservation.Outcome.NOT_DETERMINED, 0));
        assertAccounted(outcomes, 6);
        outcomes.measurementFailed(cause);
        assertAccounted(outcomes, 7);
        outcomes.transportFailed(1);

        // then
        assertAccounted(outcomes, 8);
        assertThat(outcomes.toAggregate(4))
                .isEqualTo(new ExportResponseObservation.AggregateResult(3, 1, 1, 1, 1, 1, 4, cause));
    }

    @Test
    void transportFailureConsumesOnlyItsChunkAndMeasurementKeepsFirstCause() {
        // given
        SpanOutcomeAccumulator outcomes = new SpanOutcomeAccumulator();
        outcomes.fold(observation(1, ExportResponseObservation.Outcome.ALL_ACCEPTED, 0));

        // when
        outcomes.transportFailed(1);
        IllegalStateException first = new IllegalStateException("First measurement failure");
        outcomes.measurementFailed(first);
        outcomes.measurementFailed(new IllegalStateException("Second measurement failure"));

        // then
        assertAccounted(outcomes, 4);
        assertThat(outcomes.transportDropped()).isEqualTo(1);
        assertThat(outcomes.toAggregate(2))
                .isEqualTo(new ExportResponseObservation.AggregateResult(1, 0, 1, 0, 0, 2, 2, first));
    }

    private static void assertAccounted(SpanOutcomeAccumulator outcomes, int total) {
        assertThat(outcomes.toAggregate(0).total()).isEqualTo(total);
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
