package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import java.util.Objects;
import java.util.function.Consumer;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.exporter.sender.okhttp.internal.OkHttpGrpcSenderProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.GrpcResponse;
import io.opentelemetry.sdk.common.export.GrpcSender;
import io.opentelemetry.sdk.common.export.GrpcSenderConfig;
import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.GrpcStatusCode;
import io.opentelemetry.sdk.common.export.MessageWriter;

public final class ObservingGrpcSenderProvider implements GrpcSenderProvider {

    private final GrpcSenderProvider delegate;

    @SuppressWarnings("unused")
    public ObservingGrpcSenderProvider() {
        this(new OkHttpGrpcSenderProvider());
    }

    ObservingGrpcSenderProvider(GrpcSenderProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public GrpcSender createSender(GrpcSenderConfig grpcSenderConfig) {
        return new ObservingGrpcSender(delegate.createSender(grpcSenderConfig));
    }

    private static final class ObservingGrpcSender implements GrpcSender {

        private final GrpcSender delegate;

        private ObservingGrpcSender(GrpcSender delegate) {
            this.delegate = Objects.requireNonNull(delegate, "delegate");
        }

        @Override
        public void send(MessageWriter messageWriter,
                         Consumer<GrpcResponse> onResponse,
                         Consumer<Throwable> onError) {
            ExportResponseObservation observation = ExportResponseScope.current();
            if (observation == null) {
                delegate.send(messageWriter, onResponse, onError);
                return;
            }

            delegate.send(messageWriter, response -> {
                try {
                    observe(observation, response);
                } finally {
                    onResponse.accept(response);
                }
            }, onError);
        }

        @Override
        public CompletableResultCode shutdown() {
            return delegate.shutdown();
        }

        private static void observe(ExportResponseObservation observation,
                                    GrpcResponse response) {
            try {
                if (response.getStatusCode() != GrpcStatusCode.OK) {
                    return;
                }

                OtlpTraceResponseReader.record(observation, response.getResponseMessage());
            } catch (Throwable failure) {
                PlatformThrowables.propagateIfFatal(failure);
                observation.recordNotDetermined();
            }
        }
    }
}
