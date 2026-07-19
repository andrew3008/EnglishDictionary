package space.br1440.platform.tracing.autoconfigure.kafka;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.header.Headers;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationHeaders;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;

/**
 * Kafka {@link ProducerInterceptor}, добавляющий платформенные управляющие заголовки в исходящую
 * запись на доверенные топики.
 * <p>
 * Agent-compatible: НЕ создаёт span'ы и НЕ инжектит W3C (это делает OTel Java Agent) — только
 * платформенные заголовки при положительном trusted-решении (secure-by-default).
 *
 * <h3>Контракт ProducerInterceptor</h3>
 * <ul>
 *   <li>Создаётся Kafka-клиентом по рефлексии (НЕ Spring-бин). Зависимости ({@link OutboundPropagationPolicy},
 *       {@link PlatformOutboundPropagation}) передаются через producer-config map и читаются в
 *       {@link #configure(Map)}. Kafka логирует WARN об «unknown config» для кастомных ключей —
 *       это ожидаемо.</li>
 *   <li>{@code onSend()} вызывается на producer-потоке -> inject строго неблокирующий (без I/O).</li>
 *   <li>Исключения {@code onSend()} Kafka перехватывает/логирует и не пробрасывает; дополнительно
 *       оборачиваем изоляцией (defense-in-depth), бизнес-отправка не ломается.</li>
 * </ul>
 */
public final class PlatformKafkaProducerInterceptor<K, V> implements ProducerInterceptor<K, V> {

    /** Ключ producer-config с объектом политики {@link OutboundPropagationPolicy}. */
    public static final String CONFIG_POLICY = "platform.tracing.kafka.outbound-policy";
    /** Ключ producer-config с объектом порта {@link PlatformOutboundPropagation}. */
    public static final String CONFIG_PROPAGATION = "platform.tracing.kafka.outbound-propagation";

    private OutboundPropagationPolicy policy;
    private PlatformOutboundPropagation propagation;

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        try {
            if (policy != null && propagation != null && record != null) {
                OutboundPropagationDecision decision = policy.decide(record.topic());
                apply(record.headers(), propagation.resolve(decision));
            }
        } catch (RuntimeException ignored) {
            // Изоляция: сбой propagation не должен влиять на отправку сообщения.
        }
        return record;
    }

    @Override
    public void onAcknowledgement(RecordMetadata metadata, Exception exception) {
        // Не используется: инжекция выполняется в onSend.
    }

    @Override
    public void close() {
        // Нет ресурсов для освобождения.
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
        // Если зависимости не переданы (некорректная конфигурация) — интерсептор остаётся no-op.
    }

    private static void apply(Headers carrier, OutboundPropagationHeaders headers) {
        headers.forceTrace().ifPresent(header -> set(carrier, header));
        headers.qaTrace().ifPresent(header -> set(carrier, header));
        headers.requestId().ifPresent(header -> set(carrier, header));
    }

    private static void set(Headers carrier, OutboundPropagationHeaders.Header header) {
        carrier.remove(header.name())
                .add(header.name(), header.value().getBytes(StandardCharsets.UTF_8));
    }
}
