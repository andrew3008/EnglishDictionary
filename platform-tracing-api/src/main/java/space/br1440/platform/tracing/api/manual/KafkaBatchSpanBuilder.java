package space.br1440.platform.tracing.api.manual;

import space.br1440.platform.tracing.api.semconv.annotation.KafkaSemconvVersion;

/**
 * Построитель span'а пакетного Kafka consumer, возвращаемый {@link KafkaConsumerSpanBuilder#batch(String)}.
 * <p>
 * Span'ы пакетной обработки должны использовать {@link #root()} со связями {@link #linkedTo} или
 * {@link #fromTraceparent}, заданными до {@code start()}, на контексты ссылочных сообщений.
 */
@KafkaSemconvVersion("1.28.0")
public interface KafkaBatchSpanBuilder extends ManualSpanBuilder<KafkaBatchSpanBuilder> {
}
