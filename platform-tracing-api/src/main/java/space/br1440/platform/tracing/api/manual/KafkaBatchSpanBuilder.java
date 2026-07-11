package space.br1440.platform.tracing.api.manual;

/**
 * Построитель span'а пакетного Kafka consumer, возвращаемый {@link KafkaConsumerSpanBuilder#batch(String)}.
 * <p>
 * Span'ы пакетной обработки должны использовать {@link #root()} со связями {@link #linkedTo} или
 * {@link #fromRemoteContext}, заданными до {@code start()}, на контексты ссылочных сообщений.
 */
public interface KafkaBatchSpanBuilder extends PlatformSpanBuilder<KafkaBatchSpanBuilder> {
}
