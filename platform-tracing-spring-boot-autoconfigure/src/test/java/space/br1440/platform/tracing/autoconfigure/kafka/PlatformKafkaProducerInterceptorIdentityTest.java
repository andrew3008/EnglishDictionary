package space.br1440.platform.tracing.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

class PlatformKafkaProducerInterceptorIdentityTest {

    private TracingRuntime runtime;
    private PlatformKafkaProducerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        runtime = NoOpTracingRuntime.noop();
        interceptor = new PlatformKafkaProducerInterceptor<>();
        Map<String, Object> configuration = new HashMap<>();
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_POLICY,
                (OutboundPropagationPolicy) destination ->
                        new OutboundPropagationDecision(false, false, true));
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION,
                (PlatformOutboundPropagation) decision -> OutboundPropagationHeaders.EMPTY);
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_IDENTITY,
                new RequestIdentityBoundarySupport(runtime));
        interceptor.configure(configuration);
    }

    @Test
    void forwardsCurrentRequestIdWithoutUsingCorrelationId() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "value");

        try (CorrelationScope request = runtime.openRequestIdentityScope("request-42");
             CorrelationScope correlation = runtime.openCorrelationScope("workflow-99")) {
            interceptor.onSend(record);
        }

        assertThat(header(record)).isEqualTo("request-42");
    }

    @Test
    void generatesMessageRequestIdWhenAbsentAndPreservesItOnRepeatedSend() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "value");

        interceptor.onSend(record);
        String first = header(record);
        interceptor.onSend(record);

        assertThat(first).matches("[0-9a-f-]{36}");
        assertThat(header(record)).isEqualTo(first);
    }

    private static String header(ProducerRecord<?, ?> record) {
        return new String(record.headers().lastHeader(PlatformHeaders.X_REQUEST_ID).value(),
                StandardCharsets.UTF_8);
    }
}
