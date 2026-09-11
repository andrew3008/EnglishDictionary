package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

class OtlpTraceResponseReaderTest {

    private static byte[] responseWithRejectedSpans(long rejectedSpans) {
        return ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                        .setRejectedSpans(rejectedSpans)
                        .build())
                .build()
                .toByteArray();
    }

    @Test
    @DisplayName("Пустой ответ означает, что приняты все span'ы batch'а")
    void emptyResponseAcceptsWholeBatch() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, new byte[0]);

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED);
        assertThat(observation.rejectedSpans()).isZero();
        assertThat(observation.acceptedSpans()).isEqualTo(3);
    }

    @Test
    @DisplayName("partial_success с rejected_spans уменьшает число принятых span'ов")
    void partialSuccessReducesAcceptedSpans() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, responseWithRejectedSpans(1));

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.PARTIALLY_REJECTED);
        assertThat(observation.rejectedSpans()).isEqualTo(1);
        assertThat(observation.acceptedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("error_message без отклонений остаётся предупреждением: batch принят целиком")
    void warningOnlyResponseAcceptsWholeBatch() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);
        byte[] warningOnly = ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                        .setRejectedSpans(0L)
                        .setErrorMessage("receiver is degraded")
                        .build())
                .build()
                .toByteArray();

        // when
        OtlpTraceResponseReader.record(observation, warningOnly);

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED);
        assertThat(observation.rejectedSpans()).isZero();
        assertThat(observation.acceptedSpans()).isEqualTo(3);
    }

    @Test
    @DisplayName("rejected_spans больше размера batch'а не даёт числа принятых span'ов")
    void rejectedSpansAboveBatchSizeIsNotDetermined() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, responseWithRejectedSpans(4));

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
    }

    @Test
    @DisplayName("Отрицательный rejected_spans не даёт числа принятых span'ов")
    void negativeRejectedSpansIsNotDetermined() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, responseWithRejectedSpans(-1));

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
    }

    @Test
    @DisplayName("Нечитаемый ответ не подставляет rejected_spans = 0")
    void malformedResponseIsNotDetermined() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, "not a protobuf message".getBytes(StandardCharsets.UTF_8));

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
        assertThat(observation.rejectedSpans()).isZero();
    }

    @Test
    @DisplayName("Отсутствующее тело ответа не даёт числа принятых span'ов")
    void missingResponseBodyIsNotDetermined() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, null);

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
    }

    @Test
    @DisplayName("Второй ответ для одной export operation делает число принятых span'ов неизвестным")
    void secondResponseMakesAcceptedCountAmbiguous() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);

        // when
        OtlpTraceResponseReader.record(observation, responseWithRejectedSpans(1));
        OtlpTraceResponseReader.record(observation, new byte[0]);

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
        assertThat(observation.rejectedSpans()).isZero();
    }
}
