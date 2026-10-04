package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;

import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

public final class PlatformKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    public static final String CONFIG_POLICY = "platform.tracing.kafka.outbound-policy";
    public static final String CONFIG_PROPAGATION = "platform.tracing.kafka.outbound-propagation";
    public static final String CONFIG_IDENTITY = "platform.tracing.kafka.identity-boundary";

    private OutboundPropagationPolicy policy;
    private PlatformOutboundPropagation propagation;
    private RequestIdentityBoundarySupport identityBoundary;

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        try {
            if (policy != null && propagation != null && record != null) {
                OutboundPropagationDecision decision = policy.decide(record.topic());
                InboundTraceControl control = new InboundTraceControl(
                        identityBoundary != null && identityBoundary.forceTrace(),
                        identityBoundary != null && identityBoundary.qaTrace(),
                        null, null, null
                );
                OutboundPropagationHeaders headers = propagation.resolve(decision, control);
                apply(record.headers(), headers);
            }
        } catch (RuntimeException ignored) {
            // сбой propagation не должен влиять на отправку сообщения
        }

        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // Не используется: инжекция выполняется в onSend
    }

    @Override
    public void close() {
        // Нет ресурсов для освобождения
    }

    @Override
    public void configure(Map<String, ?> configs) {
        Object policyObj = configs.get(CONFIG_POLICY);
        if (policyObj instanceof OutboundPropagationPolicy propagationPolicy) {
            this.policy = propagationPolicy;
        }

        Object propagationObj = configs.get(CONFIG_PROPAGATION);
        if (propagationObj instanceof PlatformOutboundPropagation value) {
            this.propagation = value;
        }

        Object identityObj = configs.get(CONFIG_IDENTITY);
        if (identityObj instanceof RequestIdentityBoundarySupport value) {
            this.identityBoundary = value;
        }
    }

    private void apply(Headers carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> set(carrier, header));
        headers.qaTrace().ifPresent(header -> set(carrier, header));

        if (identityBoundary == null || !identityBoundary.tracingDisabledByConfiguration()) {
            headers.requestId().ifPresent(header -> set(carrier, header));
        }

        headers.correlationId().ifPresent(header -> set(carrier, header));
    }

    private static void set(Headers carrier, OutboundPropagationHeaders.Header header) {
        String value = Objects.requireNonNull(header.value(), "value");
        carrier.remove(header.name())
                .add(header.name(), value.getBytes(StandardCharsets.UTF_8));
    }
}
