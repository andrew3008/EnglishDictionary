package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class ExportResponseObservationAggregateTest {

    @Test
    void aggregatePreservesEveryCategory() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(15);
        ExportResponseObservation.AggregateResult outcome = new ExportResponseObservation.AggregateResult(1, 2, 3, 4, 5);

        // when
        observation.recordAggregate(outcome);

        // then
        assertThat(observation.aggregateResult()).isSameAs(outcome);
        assertThat(outcome.total()).isEqualTo(15);
        assertThatThrownBy(() -> observation.recordAggregate(outcome)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(observation::recordAllAccepted).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void aggregateRejectsCountMismatchWithoutPublishing() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(2);
        ExportResponseObservation.AggregateResult outcome = new ExportResponseObservation.AggregateResult(1, 0, 0, 0, 0);

        // when
        assertThatThrownBy(() -> observation.recordAggregate(outcome)).isInstanceOf(IllegalArgumentException.class);

        // then
        assertThat(observation.aggregateResult()).isNull();
    }

    @Test
    void aggregateCannotReplaceTransportObservation() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(1);
        observation.recordAllAccepted();

        // when
        assertThatThrownBy(() -> observation.recordAggregate(new ExportResponseObservation.AggregateResult(1, 0, 0, 0, 0)))
                .isInstanceOf(IllegalStateException.class);

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED);
        assertThat(observation.aggregateResult()).isNull();
    }

    @Test
    void aggregateUsesLongTotalAndRejectsNegativeCounts() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(Integer.MAX_VALUE);
        ExportResponseObservation.AggregateResult overflow = new ExportResponseObservation.AggregateResult(
                Integer.MAX_VALUE, Integer.MAX_VALUE, 0, 0, 2);

        // when
        assertThatThrownBy(() -> observation.recordAggregate(overflow)).isInstanceOf(IllegalArgumentException.class);

        // then
        assertThat(overflow.total()).isEqualTo(4294967296L);
        assertThatThrownBy(() -> new ExportResponseObservation.AggregateResult(-1, 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
