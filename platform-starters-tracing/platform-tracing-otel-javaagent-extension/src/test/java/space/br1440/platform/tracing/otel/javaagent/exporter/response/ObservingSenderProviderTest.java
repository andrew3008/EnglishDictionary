package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.opentelemetry.proto.collector.trace.v1.ExportTracePartialSuccess;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MessageWriter;

class ObservingSenderProviderTest {

    private static byte[] partialSuccess(long rejectedSpans) {
        return ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                        .setRejectedSpans(rejectedSpans)
                        .build())
                .build()
                .toByteArray();
    }

    private static byte[] warningOnly() {
        return ExportTraceServiceResponse.newBuilder()
                .setPartialSuccess(ExportTracePartialSuccess.newBuilder()
                        .setRejectedSpans(0L)
                        .setErrorMessage("receiver is degraded")
                        .build())
                .build()
                .toByteArray();
    }

    private static byte[] malformedBody() {
        return "not a protobuf message".getBytes(StandardCharsets.UTF_8);
    }

    private static HttpSender httpSender(int statusCode, byte[] body) {
        return new HttpSender() {
            @Override
            public void send(MessageWriter messageWriter,
                             Consumer<HttpResponse> onResponse,
                             Consumer<Throwable> onError) {
                onResponse.accept(new HttpResponse() {
                    @Override
                    public int getStatusCode() {
                        return statusCode;
                    }

                    @Override
                    public String getStatusMessage() {
                        return "";
                    }

                    @Override
                    public byte[] getResponseBody() {
                        return body;
                    }
                });
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static GrpcSender grpcSender(GrpcStatusCode statusCode, byte[] message) {
        return new GrpcSender() {
            @Override
            public void send(MessageWriter messageWriter,
                             Consumer<GrpcResponse> onResponse,
                             Consumer<Throwable> onError) {
                onResponse.accept(new GrpcResponse() {
                    @Override
                    public GrpcStatusCode getStatusCode() {
                        return statusCode;
                    }

                    @Override
                    public String getStatusDescription() {
                        return null;
                    }

                    @Override
                    public byte[] getResponseMessage() {
                        return message;
                    }
                });
            }

            @Override
            public CompletableResultCode shutdown() {
                return CompletableResultCode.ofSuccess();
            }
        };
    }

    private static HttpSender observingHttpSender(int statusCode, byte[] body) {
        HttpSenderProvider delegate = config -> httpSender(statusCode, body);
        return new ObservingHttpSenderProvider(delegate).createSender(mock(HttpSenderConfig.class));
    }

    private static GrpcSender observingGrpcSender(GrpcStatusCode statusCode, byte[] message) {
        GrpcSenderProvider delegate = config -> grpcSender(statusCode, message);
        return new ObservingGrpcSenderProvider(delegate).createSender(mock(GrpcSenderConfig.class));
    }

    @Test
    @DisplayName("HTTP: partial-success ответ наблюдается для открытой export operation")
    void httpPartialSuccessIsObserved() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);
        HttpSender sender = observingHttpSender(200, partialSuccess(1));
        boolean[] responseDelivered = {false};

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> responseDelivered[0] = true, error -> {
            });
        }

        // then
        assertThat(responseDelivered[0]).isTrue();
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.PARTIALLY_REJECTED);
        assertThat(observation.acceptedSpans()).isEqualTo(2);
    }

    @Test
    @DisplayName("HTTP: error_message без отклонений не увеличивает счётчики отклонений")
    void httpWarningOnlyResponseRecordsNoRejections() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);
        HttpSender sender = observingHttpSender(200, warningOnly());

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> {
            }, error -> {
            });
        }

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED);
        assertThat(observation.rejectedSpans()).isZero();
        assertThat(observation.acceptedSpans()).isEqualTo(3);
    }

    @Test
    @DisplayName("HTTP: нечитаемое тело не отменяет успех экспорта и не подставляет rejected_spans = 0")
    void httpMalformedBodyKeepsExportSuccessfulWithoutSubstitutingZero() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);
        HttpSender sender = observingHttpSender(200, malformedBody());
        boolean[] responseDelivered = {false};
        boolean[] errorDelivered = {false};

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class),
                    response -> responseDelivered[0] = true,
                    error -> errorDelivered[0] = true);
        }

        // then
        assertThat(responseDelivered[0]).as("ответ доставлен экспортёру без изменений").isTrue();
        assertThat(errorDelivered[0]).as("наблюдение не превращается в ошибку экспорта").isFalse();
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
        assertThat(observation.rejectedSpans()).isZero();
    }

    @Test
    @DisplayName("gRPC: error_message без отклонений не увеличивает счётчики отклонений")
    void grpcWarningOnlyResponseRecordsNoRejections() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(4);
        GrpcSender sender = observingGrpcSender(GrpcStatusCode.OK, warningOnly());

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> {
            }, error -> {
            });
        }

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.ALL_ACCEPTED);
        assertThat(observation.acceptedSpans()).isEqualTo(4);
    }

    @Test
    @DisplayName("gRPC: нечитаемое сообщение не отменяет успех экспорта и не подставляет rejected_spans = 0")
    void grpcMalformedMessageKeepsExportSuccessfulWithoutSubstitutingZero() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(4);
        GrpcSender sender = observingGrpcSender(GrpcStatusCode.OK, malformedBody());
        boolean[] responseDelivered = {false};

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> responseDelivered[0] = true, error -> {
            });
        }

        // then
        assertThat(responseDelivered[0]).isTrue();
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_DETERMINED);
        assertThat(observation.rejectedSpans()).isZero();
    }

    @Test
    @DisplayName("HTTP: ответ с ошибкой не трактуется как принятые span'ы")
    void httpErrorResponseIsNotObserved() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(3);
        HttpSender sender = observingHttpSender(503, new byte[0]);

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> {
            }, error -> {
            });
        }

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_OBSERVED);
    }

    @Test
    @DisplayName("HTTP: без открытой export operation ответ доставляется без наблюдения")
    void httpWithoutScopeDelegates() {
        // given
        HttpSender sender = observingHttpSender(200, partialSuccess(1));
        boolean[] responseDelivered = {false};

        // when
        sender.send(mock(MessageWriter.class), response -> responseDelivered[0] = true, error -> {
        });

        // then
        assertThat(responseDelivered[0]).isTrue();
        assertThat(ExportResponseScope.current()).isNull();
    }

    @Test
    @DisplayName("gRPC: partial-success сообщение наблюдается для открытой export operation")
    void grpcPartialSuccessIsObserved() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(4);
        GrpcSender sender = observingGrpcSender(GrpcStatusCode.OK, partialSuccess(3));
        boolean[] responseDelivered = {false};

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> responseDelivered[0] = true, error -> {
            });
        }

        // then
        assertThat(responseDelivered[0]).isTrue();
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.PARTIALLY_REJECTED);
        assertThat(observation.acceptedSpans()).isEqualTo(1);
    }

    @Test
    @DisplayName("gRPC: статус не OK не трактуется как принятые span'ы")
    void grpcErrorStatusIsNotObserved() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(4);
        GrpcSender sender = observingGrpcSender(GrpcStatusCode.UNAVAILABLE, new byte[0]);

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            sender.send(mock(MessageWriter.class), response -> {
            }, error -> {
            });
        }

        // then
        assertThat(observation.outcome()).isEqualTo(ExportResponseObservation.Outcome.NOT_OBSERVED);
    }

    @Test
    @DisplayName("Scope закрывается и не протекает за пределы export operation")
    void scopeDoesNotLeak() {
        // given
        ExportResponseObservation observation = new ExportResponseObservation(1);

        // when
        try (ExportResponseScope.Handle ignored = ExportResponseScope.open(observation)) {
            assertThat(ExportResponseScope.current()).isSameAs(observation);
        }

        // then
        assertThat(ExportResponseScope.current()).isNull();
    }
}
