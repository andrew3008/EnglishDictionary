package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import org.apache.kafka.clients.producer.ProducerRecord;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformKafkaProducerInterceptorTest {

    @Test
    void writesCorrelationIdHeaderWhenPropagationResolvesOne() {
        // given
        OutboundPropagationHeaders.Header correlationHeader =
                new OutboundPropagationHeaders.Header(PlatformHeaders.X_CORRELATION_ID, "order-42");
        PlatformOutboundPropagation propagation = (decision, control) -> new OutboundPropagationHeaders(
                Optional.empty(), Optional.empty(), Optional.empty(), Optional.of(correlationHeader));
        PlatformKafkaProducerInterceptor<String, String> interceptor =
                interceptorWith(destination -> OutboundPropagationDecision.DENY_ALL, propagation);
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");

        // when
        interceptor.onSend(record);

        // then
        assertThat(headerValue(record, PlatformHeaders.X_CORRELATION_ID)).isEqualTo("order-42");
    }

    @Test
    void doesNotWriteCorrelationIdHeaderWhenPropagationResolvesNone() {
        // given
        PlatformOutboundPropagation propagation = (decision, control) -> OutboundPropagationHeaders.EMPTY;
        PlatformKafkaProducerInterceptor<String, String> interceptor =
                interceptorWith(destination -> OutboundPropagationDecision.DENY_ALL, propagation);
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");

        // when
        interceptor.onSend(record);

        // then
        assertThat(record.headers().lastHeader(PlatformHeaders.X_CORRELATION_ID)).isNull();
    }

    @Test
    void writesAllFourHeadersWhenPropagationResolvesThem() {
        // given
        OutboundPropagationHeaders.Header forceTraceHeader =
                new OutboundPropagationHeaders.Header(PlatformHeaders.X_TRACE_ON, "on");
        OutboundPropagationHeaders.Header qaTraceHeader =
                new OutboundPropagationHeaders.Header(PlatformHeaders.X_QA_TRACE, "on");
        OutboundPropagationHeaders.Header requestIdHeader =
                new OutboundPropagationHeaders.Header(PlatformHeaders.X_REQUEST_ID, "req-1");
        OutboundPropagationHeaders.Header correlationHeader =
                new OutboundPropagationHeaders.Header(PlatformHeaders.X_CORRELATION_ID, "order-42");
        PlatformOutboundPropagation propagation = (decision, control) -> new OutboundPropagationHeaders(
                Optional.of(forceTraceHeader), Optional.of(qaTraceHeader),
                Optional.of(requestIdHeader), Optional.of(correlationHeader));
        PlatformKafkaProducerInterceptor<String, String> interceptor =
                interceptorWith(destination -> OutboundPropagationDecision.DENY_ALL, propagation);
        ProducerRecord<String, String> record = new ProducerRecord<>("orders", "key", "value");

        // when
        interceptor.onSend(record);

        // then
        assertThat(headerValue(record, PlatformHeaders.X_TRACE_ON)).isEqualTo("on");
        assertThat(headerValue(record, PlatformHeaders.X_QA_TRACE)).isEqualTo("on");
        assertThat(headerValue(record, PlatformHeaders.X_REQUEST_ID)).isEqualTo("req-1");
        assertThat(headerValue(record, PlatformHeaders.X_CORRELATION_ID)).isEqualTo("order-42");
    }

    private static PlatformKafkaProducerInterceptor<String, String> interceptorWith(OutboundPropagationPolicy policy,
                                                                                    PlatformOutboundPropagation propagation) {
        PlatformKafkaProducerInterceptor<String, String> interceptor = new PlatformKafkaProducerInterceptor<>();
        interceptor.configure(Map.of(
                PlatformKafkaProducerInterceptor.CONFIG_POLICY, policy,
                PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION, propagation
        ));
        return interceptor;
    }

    private static String headerValue(ProducerRecord<String, String> record, String headerName) {
        return new String(record.headers().lastHeader(headerName).value(), StandardCharsets.UTF_8);
    }
}
