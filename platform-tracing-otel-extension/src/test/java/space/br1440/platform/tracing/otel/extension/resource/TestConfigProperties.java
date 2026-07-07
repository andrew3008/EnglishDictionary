package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Минимальная in-memory реализация {@link ConfigProperties} для unit-тестов resource-пакета.
 * <p>
 * {@code getString} читает из карты; {@code getMap} возвращает пустую карту (для
 * {@code otel.resource.attributes}). Для эмуляции {@code OTEL_SERVICE_NAME} задавайте ключ
 * {@code otel.service.name}.
 */
final class TestConfigProperties implements ConfigProperties {

    private final Map<String, String> data;

    TestConfigProperties(Map<String, String> data) {
        this.data = data;
    }

    @Override
    public String getString(String name) {
        return data.get(name);
    }

    @Override
    public Boolean getBoolean(String name) {
        String value = data.get(name);
        return value == null ? null : Boolean.parseBoolean(value);
    }

    @Override
    public Integer getInt(String name) {
        String value = data.get(name);
        return value == null ? null : Integer.valueOf(value);
    }

    @Override
    public Long getLong(String name) {
        String value = data.get(name);
        return value == null ? null : Long.valueOf(value);
    }

    @Override
    public Double getDouble(String name) {
        String value = data.get(name);
        return value == null ? null : Double.valueOf(value);
    }

    @Override
    public Duration getDuration(String name) {
        return null;
    }

    @Override
    public List<String> getList(String name) {
        return Collections.emptyList();
    }

    @Override
    public Map<String, String> getMap(String name) {
        return Collections.emptyMap();
    }
}
