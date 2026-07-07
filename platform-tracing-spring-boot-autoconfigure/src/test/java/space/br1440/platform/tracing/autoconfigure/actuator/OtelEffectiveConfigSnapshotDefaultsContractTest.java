package space.br1440.platform.tracing.autoconfigure.actuator;

import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.extension.configuration.spi.PlatformTracingDefaultsProvider;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт: {@link OtelEffectiveConfigSnapshot} PLATFORM_DEFAULTS синхронизированы с
 * {@link PlatformTracingDefaultsProvider#supply()} (extension SPI).
 */
class OtelEffectiveConfigSnapshotDefaultsContractTest {

    @Test
    void platformDefaults_в_otelEffective_совпадают_с_extension_SPI() {
        Map<String, String> extensionDefaults = new PlatformTracingDefaultsProvider().supply();
        OtelEffectiveConfigSnapshot snapshot = new OtelEffectiveConfigSnapshot(key -> null, key -> null);
        Map<String, Map<String, Object>> effective = snapshot.build();

        effective.forEach((key, entry) -> {
            if ("default-platform".equals(entry.get("source"))) {
                assertThat(extensionDefaults).containsKey(key);
                assertThat(entry.get("value")).isEqualTo(extensionDefaults.get(key));
            }
        });

        extensionDefaults.forEach((key, expectedValue) -> {
            if (!effective.containsKey(key)) {
                return;
            }
            assertThat(effective.get(key))
                    .containsEntry("source", "default-platform")
                    .containsEntry("value", expectedValue);
        });
    }
}
