package space.br1440.platform.tracing.e2e.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

import java.time.Duration;

/**
 * Общая настройка OTel Collector для e2e на remote Docker (Gentoo).
 * <p>
 * Collector → Jaeger экспортирует по IP контейнера (не по DNS alias): на Gentoo внутренний
 * Docker DNS между контейнерами ненадёжен.
 */
public final class OtelCollectorTestContainerSupport {

    public static final String IMAGE = "otel/opentelemetry-collector-contrib:0.154.0";
    public static final String E2E_CONFIG_RESOURCE = "e2e/otel-collector-e2e.yaml";
    public static final String JAEGER_OTLP_GRPC_ENDPOINT_ENV = "JAEGER_OTLP_GRPC_ENDPOINT";
    public static final int OTLP_HTTP_PORT = 4318;
    public static final int HEALTH_PORT = 13133;

    private OtelCollectorTestContainerSupport() {
    }

    /**
     * E2e Collector с tail_sampling YAML; Jaeger должен быть уже {@code start()}-нут.
     */
    public static GenericContainer<?> newE2eCollector(Network network, GenericContainer<?> jaeger) {
        return new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withNetwork(network)
                .withNetworkAliases("otel-collector")
                .withEnv(JAEGER_OTLP_GRPC_ENDPOINT_ENV,
                        JaegerTestContainerSupport.jaegerOtlpGrpcEndpointOnNetwork(jaeger))
                .withCopyFileToContainer(
                        MountableFile.forClasspathResource(E2E_CONFIG_RESOURCE),
                        "/etc/otelcol-contrib/config.yaml")
                .withExposedPorts(OTLP_HTTP_PORT, HEALTH_PORT)
                .waitingFor(Wait.forHttp("/").forPort(HEALTH_PORT).withStartupTimeout(Duration.ofMinutes(2)))
                .dependsOn(jaeger);
    }

    /** OTLP/HTTP traces endpoint с хоста теста (IP docker daemon, не DNS). */
    public static String otlpHttpTracesEndpointFromHost(GenericContainer<?> collector) {
        return "http://" + collector.getHost() + ":" + collector.getMappedPort(OTLP_HTTP_PORT) + "/v1/traces";
    }
}
