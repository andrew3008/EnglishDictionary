package space.br1440.platform.tracing.autoconfigure.kafka;

import io.opentelemetry.context.Context;
import org.apache.kafka.clients.producer.ProducerInterceptor;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundInjector;
import space.br1440.platform.tracing.api.propagation.control.PlatformPropagationDecision;
import space.br1440.platform.tracing.api.propagation.control.PlatformTraceContextKeys;

import java.util.Map;

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
 *       {@link PlatformOutboundInjector}) передаются через producer-config map и читаются в
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
    /** Ключ producer-config с объектом инжектора {@link PlatformOutboundInjector}. */
    public static final String CONFIG_INJECTOR = "platform.tracing.kafka.outbound-injector";

    private OutboundPropagationPolicy policy;
    private PlatformOutboundInjector injector;

    @Override
    public ProducerRecord<K, V> onSend(ProducerRecord<K, V> record) {
        try {
            if (policy != null && injector != null && record != null) {
                PlatformPropagationDecision decision = policy.decide(record.topic());
                Context decided = Context.current().with(PlatformTraceContextKeys.PROPAGATION_DECISION, decision);
                injector.inject(decided, record.headers(), PlatformKafkaHeaderSetter.INSTANCE);
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
        Object injectorObj = configs.get(CONFIG_INJECTOR);
        if (injectorObj instanceof PlatformOutboundInjector i) {
            this.injector = i;
        }
        // Если зависимости не переданы (некорректная конфигурация) — интерсептор остаётся no-op.
    }
}
