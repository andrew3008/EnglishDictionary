package space.br1440.platform.tracing.e2e.smoke;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.DisabledIfSystemProperty;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;
import space.br1440.platform.tracing.e2e.support.OtelCollectorSpanExportReader;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверка инфраструктуры OTel Collector file exporter (Option C) для remote Docker.
 * <p>
 * Отдельный от {@link PlatformExtensionAgentSmokeTest}: здесь только pipeline Collector → NDJSON,
 * без Java Agent (SDK smoke через curl/agent покрыт другими тестами).
 */
@Testcontainers
@DisabledIfSystemProperty(named = "skipE2e", matches = "true",
        disabledReason = "Smoke-тесты пропущены через -DskipE2e=true")
class OtelCollectorFileExporterSmokeTest {

    private static final String COLLECTOR_EXPORT_PATH = "/tmp/spans.json";

    private static Network network;
    private static GenericContainer<?> collector;

    @BeforeAll
    static void setUpCollector() {
        network = Network.newNetwork();

        collector = new GenericContainer<>(DockerImageName.parse("otel/opentelemetry-collector-contrib:0.154.0"))
                .withNetwork(network)
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("e2e/otel-collector-file-exporter.yaml"),
                        "/etc/otelcol-contrib/config.yaml")
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource("e2e/empty-spans-seed.json"),
                        COLLECTOR_EXPORT_PATH)
                // scratch-образ: file exporter требует writable path; non-root user не может писать seed.
                .withCreateContainerCmdModifier(cmd -> cmd.withUser("0:0"))
                .withExposedPorts(4317, 13133)
                .waitingFor(Wait.forHttp("/").forPort(13133).withStartupTimeout(Duration.ofMinutes(2)));
        collector.start();
    }

    @AfterAll
    static void tearDownCollector() {
        if (collector != null) {
            collector.stop();
        }
        if (network != null) {
            network.close();
        }
    }

    /**
     * Collector с file exporter поднимается и health check доступен.
     * Полный OTLP→file roundtrip для extension покрывается {@link PlatformExtensionAgentSmokeTest} (Jaeger path).
     */
    @Test
    void collector_file_exporter_доступен_и_экспортный_файл_существует() throws Exception {
        assertThat(collector.isRunning()).isTrue();

        Path tempFile = Files.createTempFile("otel-spans-", ".json");
        collector.copyFileFromContainer(COLLECTOR_EXPORT_PATH, tempFile.toString());

        // seed-файл скопирован — инфраструктура copyFileFromContainer работает на remote Docker.
        assertThat(Files.exists(tempFile)).isTrue();

        List<Map<String, String>> spans = OtelCollectorSpanExportReader.readAllSpanStringAttributes(tempFile);
        assertThat(spans).isNotNull();
    }
}
