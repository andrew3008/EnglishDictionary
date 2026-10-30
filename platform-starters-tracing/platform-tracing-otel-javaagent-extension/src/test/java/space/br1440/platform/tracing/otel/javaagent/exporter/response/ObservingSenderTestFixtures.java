package space.br1440.platform.tracing.otel.javaagent.exporter.response;

import io.opentelemetry.sdk.common.export.GrpcSenderProvider;
import io.opentelemetry.sdk.common.export.HttpSenderProvider;

public final class ObservingSenderTestFixtures {

    private ObservingSenderTestFixtures() {
    }

    public static GrpcSenderProvider grpc(GrpcSenderProvider delegate) {
        return new ObservingGrpcSenderProvider(delegate);
    }

    public static HttpSenderProvider http(HttpSenderProvider delegate) {
        return new ObservingHttpSenderProvider(delegate);
    }
}
