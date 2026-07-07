package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class OtelEffectiveConfigSnapshotTest {

    @Test
    void toEnvName_конвертирует_otel_property_в_OTEL_env_имя() {
        // Стандартное OTel-соглашение: точки → подчёркивания, верхний регистр.
        assertThat(OtelEffectiveConfigSnapshot.toEnvName("otel.bsp.max.queue.size"))
                .isEqualTo("OTEL_BSP_MAX_QUEUE_SIZE");
        assertThat(OtelEffectiveConfigSnapshot.toEnvName("otel.exporter.otlp.endpoint"))
                .isEqualTo("OTEL_EXPORTER_OTLP_ENDPOINT");
    }

    @Test
    void maskIfSensitive_маскирует_заголовки() {
        // OTEL_EXPORTER_OTLP_HEADERS — самый частый источник секретов (Authorization-токены).
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive(
                "otel.exporter.otlp.headers", "authorization=Bearer abc")).isEqualTo("***");
    }

    @Test
    void maskIfSensitive_маскирует_токены_пароли_секреты() {
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive("any.api.token", "x")).isEqualTo("***");
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive("any.password", "x")).isEqualTo("***");
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive("any.client.secret", "x")).isEqualTo("***");
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive("any.api.key", "x")).isEqualTo("***");
    }

    @Test
    void maskIfSensitive_не_трогает_безопасные_значения() {
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive(
                "otel.bsp.max.queue.size", "2048")).isEqualTo("2048");
        assertThat(OtelEffectiveConfigSnapshot.maskIfSensitive(
                "otel.exporter.otlp.endpoint", "http://collector:4317"))
                .isEqualTo("http://collector:4317");
    }

    @Test
    void build_система_свойств_имеет_приоритет_над_env_vars() {
        Map<String, String> sysProps = new HashMap<>();
        Map<String, String> envVars = new HashMap<>();
        sysProps.put("otel.bsp.max.queue.size", "8192");
        envVars.put("OTEL_BSP_MAX_QUEUE_SIZE", "4096");

        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                sysProps::get, envVars::get);
        Map<String, Map<String, Object>> result = snapshot.build();

        Map<String, Object> entry = result.get("otel.bsp.max.queue.size");
        assertThat(entry.get("source")).isEqualTo("system-property");
        assertThat(entry.get("value")).isEqualTo("8192");
        assertThat(entry.get("envVarName")).isEqualTo("OTEL_BSP_MAX_QUEUE_SIZE");
    }

    @Test
    void build_env_var_используется_если_системное_свойство_отсутствует() {
        Map<String, String> sysProps = new HashMap<>();
        Map<String, String> envVars = new HashMap<>();
        envVars.put("OTEL_BSP_MAX_QUEUE_SIZE", "4096");

        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                sysProps::get, envVars::get);
        Map<String, Map<String, Object>> result = snapshot.build();

        Map<String, Object> entry = result.get("otel.bsp.max.queue.size");
        assertThat(entry.get("source")).isEqualTo("env-var");
        assertThat(entry.get("value")).isEqualTo("4096");
    }

    @Test
    void build_default_platform_когда_свойство_имеет_платформенный_дефолт() {
        // У otel.bsp.max.queue.size есть платформенный default (extension SPI) → подменяется
        // источник "default-platform", значение читается из PLATFORM_DEFAULTS map.
        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                p -> null, e -> null);
        Map<String, Map<String, Object>> result = snapshot.build();

        Map<String, Object> entry = result.get("otel.bsp.max.queue.size");
        assertThat(entry.get("source")).isEqualTo("default-platform");
        assertThat(entry.get("value")).isEqualTo("2048");
    }

    @Test
    void build_default_otel_sdk_когда_платформенного_дефолта_нет() {
        // У otel.exporter.otlp.endpoint платформенного default'а нет: дефолт берётся самим
        // OTel SDK ("http://localhost:4317"). actuator-снапшот возвращает source=default-otel-sdk
        // и value=null, чтобы не вводить оператора в заблуждение «фейковыми» значениями.
        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                p -> null, e -> null);
        Map<String, Map<String, Object>> result = snapshot.build();

        Map<String, Object> entry = result.get("otel.exporter.otlp.endpoint");
        assertThat(entry.get("source")).isEqualTo("default-otel-sdk");
        assertThat(entry.get("value")).isNull();
    }

    @Test
    void build_маскирует_заголовки_и_не_трогает_остальное() {
        Map<String, String> envVars = new HashMap<>();
        envVars.put("OTEL_EXPORTER_OTLP_HEADERS", "authorization=Bearer secret123");
        envVars.put("OTEL_EXPORTER_OTLP_ENDPOINT", "http://collector:4317");

        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                p -> null, envVars::get);
        Map<String, Map<String, Object>> result = snapshot.build();

        // Headers замаскированы.
        assertThat(result.get("otel.exporter.otlp.headers").get("value")).isEqualTo("***");
        // Endpoint не трогаем — это публичный URL.
        assertThat(result.get("otel.exporter.otlp.endpoint").get("value"))
                .isEqualTo("http://collector:4317");
    }

    @Test
    void build_возвращает_лексикографически_упорядоченную_карту() {
        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(
                p -> null, e -> null);
        Map<String, Map<String, Object>> result = snapshot.build();

        // TreeMap гарантирует упорядоченный обход — это упрощает diff'ы между окружениями.
        assertThat(result).isNotEmpty();
        String previous = "";
        for (String key : result.keySet()) {
            assertThat(key.compareTo(previous)).isPositive();
            previous = key;
        }
    }
}
