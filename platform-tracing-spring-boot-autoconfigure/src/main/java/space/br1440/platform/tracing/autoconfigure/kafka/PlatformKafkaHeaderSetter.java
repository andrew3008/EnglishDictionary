package space.br1440.platform.tracing.autoconfigure.kafka;

import io.opentelemetry.context.propagation.TextMapSetter;
import org.apache.kafka.common.header.Headers;

import java.nio.charset.StandardCharsets;

/**
 * {@link TextMapSetter} для записи платформенных заголовков в Kafka {@link Headers}.
 * <p>
 * Kafka headers — бинарные и case-sensitive. Перезаписываем ключ ({@code remove} + {@code add})
 * во избежание дублей при повторной инжекции; значение кодируем в UTF-8.
 */
public enum PlatformKafkaHeaderSetter implements TextMapSetter<Headers> {

    INSTANCE;

    @Override
    public void set(Headers headers, String key, String value) {
        if (headers != null && value != null) {
            headers.remove(key).add(key, value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
