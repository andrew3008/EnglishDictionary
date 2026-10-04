package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class PlatformKafkaProducerInterceptorDisabledRuntimeTest {

    @Test
    void disabledConfigurationDoesNotComposeRequestId() {
        TracingRuntime runtime = NoOpTracingRuntime.disabledByConfiguration("platform.tracing.enabled=false");
        RequestIdentityBoundarySupport identityBoundary = new RequestIdentityBoundarySupport(runtime);

        try (PlatformKafkaProducerInterceptor<String, String> interceptor = interceptor(identityBoundary);
             CorrelationScope ignored = runtime.openRequestIdentityScope("request-42");
             CorrelationScope ignored1 = runtime.openCorrelationScope("workflow-99")) {
            assertThat(runtime.currentRequestId()).contains("request-42");
            assertThat(runtime.currentCorrelationId()).contains("workflow-99");

            ProducerRecord<String, String> bare = new ProducerRecord<>("orders", "v");
            interceptor.onSend(bare);
            assertThat(bare.headers().lastHeader(PlatformHeaders.X_REQUEST_ID)).isNull();
            assertThat(header(bare, PlatformHeaders.X_TRACE_ON)).isEqualTo("on");
            assertThat(header(bare, PlatformHeaders.X_CORRELATION_ID)).isEqualTo("corr-from-resolver");

            ProducerRecord<String, String> second = new ProducerRecord<>("orders", "v");
            interceptor.onSend(second);
            assertThat(second.headers().lastHeader(PlatformHeaders.X_REQUEST_ID)).isNull();

            ProducerRecord<String, String> preset = new ProducerRecord<>("orders", "v");
            preset.headers().add(PlatformHeaders.X_REQUEST_ID, bytes("preset-id"));
            interceptor.onSend(preset);
            assertThat(header(preset, PlatformHeaders.X_REQUEST_ID)).isEqualTo("preset-id");
        }
    }

    private static PlatformKafkaProducerInterceptor<String, String> interceptor(RequestIdentityBoundarySupport identityBoundary) {
        PlatformOutboundPropagation propagation = (decision, control) -> new OutboundPropagationHeaders(
                Optional.of(new OutboundPropagationHeaders.Header(PlatformHeaders.X_TRACE_ON, "on")),
                Optional.empty(),
                Optional.of(new OutboundPropagationHeaders.Header(PlatformHeaders.X_REQUEST_ID, "user-composed")),
                Optional.of(new OutboundPropagationHeaders.Header(PlatformHeaders.X_CORRELATION_ID, "corr-from-resolver"))
        );

        Map<String, Object> configuration = new HashMap<>();
        configuration.put(
                PlatformKafkaProducerInterceptor.CONFIG_POLICY,
                (OutboundPropagationPolicy) destination -> OutboundPropagationDecision.ALLOW_ALL
        );

        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION, propagation);
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_IDENTITY, identityBoundary);

        PlatformKafkaProducerInterceptor<String, String> interceptor = new PlatformKafkaProducerInterceptor<>();
        interceptor.configure(configuration);
        return interceptor;
    }

    private static String header(ProducerRecord<?, ?> record, String name) {
        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);
    }

    @SuppressWarnings("SameParameterValue")
    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
