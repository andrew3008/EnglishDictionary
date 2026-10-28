package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.config.ConfigException;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.header.Headers;

import space.br1440.platform.tracing.api.propagation.PlatformHeaders;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

import lombok.extern.slf4j.Slf4j;

@Slf4j
public final class PlatformKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    public static final String CONFIG_POLICY = "platform.tracing.kafka.outbound-policy";
    public static final String CONFIG_PROPAGATION = "platform.tracing.kafka.outbound-propagation";
    public static final String CONFIG_IDENTITY = "platform.tracing.kafka.identity-boundary";

    private OutboundPropagationPolicy policy;
    private PlatformOutboundPropagation propagation;
    private RequestIdentityBoundarySupport identityBoundary;
    private final AtomicBoolean propagationFailureLogged = new AtomicBoolean();

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        try {
            strip(record.headers());
            OutboundPropagationDecision decision = policy.decide(record.topic());
            InboundTraceControl control = new InboundTraceControl(
                    identityBoundary.forceTrace(), identityBoundary.qaTrace(), null, null, null
            );
            apply(record.headers(), propagation.resolve(decision, control));
        } catch (RuntimeException failure) {
            if (propagationFailureLogged.compareAndSet(false, true)) {
                log.warn("Failed to prepare platform propagation headers: {}", failure.getClass().getName());
            }
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
        if (!(configs.get(CONFIG_POLICY) instanceof OutboundPropagationPolicy configuredPolicy)) {
            throw new ConfigException("Missing or invalid configuration: " + CONFIG_POLICY);
        }
        if (!(configs.get(CONFIG_PROPAGATION) instanceof PlatformOutboundPropagation configuredPropagation)) {
            throw new ConfigException("Missing or invalid configuration: " + CONFIG_PROPAGATION);
        }
        if (!(configs.get(CONFIG_IDENTITY) instanceof RequestIdentityBoundarySupport configuredIdentity)) {
            throw new ConfigException("Missing or invalid configuration: " + CONFIG_IDENTITY);
        }

        policy = configuredPolicy;
        propagation = configuredPropagation;
        identityBoundary = configuredIdentity;
    }

    private static void strip(Headers carrier) {
        Iterator<Header> iterator = carrier.iterator();
        while (iterator.hasNext()) {
            String name = iterator.next().key();
            for (String reserved : PlatformHeaders.OUTBOUND_CONTROL_HEADERS) {
                if (reserved.equalsIgnoreCase(name)) {
                    iterator.remove();
                    break;
                }
            }
        }
    }

    private static void apply(Headers carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(value -> set(carrier, PlatformHeaders.X_TRACE_ON, value));
        headers.qaTrace().ifPresent(value -> set(carrier, PlatformHeaders.X_QA_TRACE, value));
        headers.requestId().ifPresent(value -> set(carrier, PlatformHeaders.X_REQUEST_ID, value));
        headers.correlationId().ifPresent(value -> set(carrier, PlatformHeaders.X_CORRELATION_ID, value));
    }

    private static void set(Headers carrier, String name, String value) {
        carrier.remove(name).add(name, value.getBytes(StandardCharsets.UTF_8));
    }
}
