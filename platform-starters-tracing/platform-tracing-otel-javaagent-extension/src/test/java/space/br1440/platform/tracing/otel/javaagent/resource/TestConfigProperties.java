package space.br1440.platform.tracing.otel.javaagent.resource;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

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
        return (value == null) ? null : Boolean.parseBoolean(value);
    }

    @Override
    public Integer getInt(String name) {
        String value = data.get(name);
        return (value == null) ? null : Integer.valueOf(value);
    }

    @Override
    public Long getLong(String name) {
        String value = data.get(name);
        return (value == null) ? null : Long.valueOf(value);
    }

    @Override
    public Double getDouble(String name) {
        String value = data.get(name);
        return (value == null) ? null : Double.valueOf(value);
    }

    @Override
    public Duration getDuration(String name) {
        return null;
    }

    @Override
    public List<String> getList(String name) {
        String value = data.get(name);
        if (StringUtils.isNullOrBlank(value)) {
            return Collections.emptyList();
        }
        return Arrays.stream(value.split(","))
                .map(String::trim)
                .filter(element -> !element.isEmpty())
                .toList();
    }

    @Override
    public Map<String, String> getMap(String name) {
        String value = data.get(name);
        if (StringUtils.isNullOrBlank(value)) {
            return Collections.emptyMap();
        }

        Map<String, String> parsed = new LinkedHashMap<>();
        for (String element : value.split(",")) {
            int separator = element.indexOf('=');
            if (separator <= 0) {
                continue;
            }

            parsed.put(element.substring(0, separator).trim(), element.substring(separator + 1).trim());
        }

        return Map.copyOf(parsed);
    }
}
