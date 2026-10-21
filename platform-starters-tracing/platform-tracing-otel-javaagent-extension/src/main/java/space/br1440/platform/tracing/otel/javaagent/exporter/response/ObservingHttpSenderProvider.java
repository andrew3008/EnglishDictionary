package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import java.util.Objects;
import java.util.function.Consumer;

import space.br1440.platform.tracing.otel.javaagent.resilience.PlatformThrowables;

import io.opentelemetry.exporter.sender.okhttp.internal.OkHttpHttpSenderProvider;
import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.common.export.HttpResponse;
import io.opentelemetry.sdk.common.export.HttpSender;
import io.opentelemetry.sdk.common.export.HttpSenderConfig;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;
import io.opentelemetry.sdk.common.export.MessageWriter;

public final class ObservingHttpSenderProvider implements HttpSenderProvider {

    private final HttpSenderProvider delegate;

    @SuppressWarnings("unused")
    public ObservingHttpSenderProvider() {
        this(new OkHttpHttpSenderProvider());
    }

    ObservingHttpSenderProvider(HttpSenderProvider delegate) {
        this.delegate = Objects.requireNonNull(delegate, "delegate");
    }

    @Override
    public HttpSender createSender(HttpSenderConfig httpSenderConfig) {
        return new ObservingHttpSender(delegate.createSender(httpSenderConfig));
    }

    private record ObservingHttpSender(HttpSender delegate) implements HttpSender {

            private ObservingHttpSender(HttpSender delegate) {
                this.delegate = Objects.requireNonNull(delegate, "delegate");
            }

            @Override
            public void send(MessageWriter messageWriter,
                             Consumer<HttpResponse> onResponse,
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
                                        HttpResponse response) {
                try {
                    if (response.getStatusCode() / 100 != 2) {
                        return;
                    }

                    OtlpTraceResponseReader.record(observation, response.getResponseBody());
                } catch (Throwable failure) {
                    PlatformThrowables.propagateIfFatal(failure);
                    observation.recordNotDetermined();
                }
            }
        }
}
