package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Objects;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdBoundarySupport;
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
                OutboundPropagationHeaders headers = propagation.resolve(decision);
                apply(record.headers(), headers);
                ensureRequestId(record.headers(), decision, headers);
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
        if (policyObj instanceof OutboundPropagationPolicy p) {
            this.policy = p;
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

    private static void apply(Headers carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> set(carrier, header));
        headers.qaTrace().ifPresent(header -> set(carrier, header));
        headers.requestId().ifPresent(header -> set(carrier, header));
    }

    private static void set(Headers carrier, OutboundPropagationHeaders.Header header) {
        String value = Objects.requireNonNull(header.value(), "value");
        carrier.remove(header.name())
                .add(header.name(), value.getBytes(StandardCharsets.UTF_8));
    }

    private void ensureRequestId(Headers carrier,
                                 OutboundPropagationDecision decision,
                                 OutboundPropagationHeaders resolved) {
        if (!decision.propagateRequestId() || resolved.requestId().isPresent()) {
            return;
        }

        Header existing = carrier.lastHeader(PlatformHeaders.X_REQUEST_ID);
        String raw = (existing == null || existing.value() == null)
                ? null
                : new String(existing.value(), StandardCharsets.UTF_8);

        String current = (identityBoundary == null)
                ? null
                : identityBoundary.requestId().orElse(null);

        String requestId = RequestIdBoundarySupport.resolve(current != null ? current : raw);
        carrier.remove(PlatformHeaders.X_REQUEST_ID)
                .add(PlatformHeaders.X_REQUEST_ID, requestId.getBytes(StandardCharsets.UTF_8));
    }
}
