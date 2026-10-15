package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.context.CorrelationScope;
import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.runtime.propagation.control.DefaultTraceControlHeaderInjector;
import space.br1440.platform.tracing.runtime.propagation.control.OutboundApplicationIdentitySource;

class PlatformKafkaProducerInterceptorIdentityTest {

    private TracingRuntime runtime;
    private RequestIdentityBoundarySupport identityBoundary;
    private PlatformKafkaProducerInterceptor<String, String> interceptor;

    @BeforeEach
    void setUp() {
        runtime = NoOpTracingRuntime.noop();
        identityBoundary = new RequestIdentityBoundarySupport(runtime);
        interceptor = new PlatformKafkaProducerInterceptor<>();
        PlatformOutboundPropagation propagation = new DefaultTraceControlHeaderInjector(
                OutboundApplicationIdentitySource.of(
                        () -> identityBoundary.requestId().orElse(null),
                        () -> null
                )
        );

        Map<String, Object> configuration = new HashMap<>();
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_POLICY,
                (OutboundPropagationPolicy) destination ->
                        new OutboundPropagationDecision(false, false, true, false));
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION, propagation);
        configuration.put(PlatformKafkaProducerInterceptor.CONFIG_IDENTITY, identityBoundary);
        interceptor.configure(configuration);
    }

    @Test
    void forwardsCurrentRequestIdWithoutUsingCorrelationId() {
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "value");

        try (CorrelationScope ignored = runtime.openRequestIdentityScope("request-42");
             CorrelationScope ignored1 = runtime.openCorrelationScope("workflow-99")) {
            interceptor.onSend(record);
        }

        assertThat(header(record)).isEqualTo("request-42");
    }

    @Test
    void noHeaderIsSetWhenNoIdentityScopeIsOpen() {
        // given
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "value");

        // when
        interceptor.onSend(record);

        // then
        assertThat(record.headers().lastHeader(PlatformHeaders.X_REQUEST_ID)).isNull();
    }

    private static String header(ProducerRecord<?, ?> record) {
        byte[] requestId = record.headers().lastHeader(PlatformHeaders.X_REQUEST_ID).value();
        return new String(requestId, StandardCharsets.UTF_8);
    }
}
