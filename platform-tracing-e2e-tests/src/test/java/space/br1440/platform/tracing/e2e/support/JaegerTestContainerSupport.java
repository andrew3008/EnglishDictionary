package space.br1440.platform.tracing.e2e.support;

import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * Общая настройка Jaeger all-in-one для e2e/smoke на remote Docker (Gentoo).
 * <p>
 * OTLP HTTP ({@value #OTLP_HTTP_PORT}) надёжнее gRPC при экспорте с Windows-хоста
 * в контейнер на удалённом daemon: mapped ephemeral-порты gRPC часто не проходят.
 */
public final class JaegerTestContainerSupport {

    public static final String IMAGE = "jaegertracing/all-in-one:1.62.0";
    public static final int QUERY_PORT = 16686;
    public static final int OTLP_HTTP_PORT = 4318;

    private JaegerTestContainerSupport() {
    }

    public static GenericContainer<?> newJaeger() {
        return newJaeger(null);
    }

    public static GenericContainer<?> newJaeger(Network network) {
        GenericContainer<?> container = new GenericContainer<>(DockerImageName.parse(IMAGE))
                .withEnv("COLLECTOR_OTLP_ENABLED", "true")
                .withExposedPorts(QUERY_PORT, OTLP_HTTP_PORT)
                .waitingFor(Wait.forHttp("/").forPort(QUERY_PORT).withStartupTimeout(Duration.ofMinutes(2)));
        if (network != null) {
            container.withNetwork(network).withNetworkAliases("jaeger");
        }
        return container;
    }

    public static String queryBaseUrl(GenericContainer<?> jaeger) {
        return "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(QUERY_PORT);
    }

    /** OTLP HTTP base (host:port без path) — для health-check и curl-диагностики. */
    public static String otlpHttpEndpoint(GenericContainer<?> jaeger) {
        return "http://" + jaeger.getHost() + ":" + jaeger.getMappedPort(OTLP_HTTP_PORT);
    }

    /**
     * Полный OTLP/HTTP traces endpoint ({@code .../v1/traces}).
     * Jaeger и OTel SDK/agent POST'ят span'ы только на этот path.
     */
    public static String otlpHttpTracesEndpoint(GenericContainer<?> jaeger) {
        return otlpHttpEndpoint(jaeger) + "/v1/traces";
    }

    /** Полный OTLP/HTTP traces endpoint из base или уже полного URL. */
    public static String resolveOtlpHttpTracesEndpoint(String otlpBaseOrTracesEndpoint) {
        return otlpBaseOrTracesEndpoint.endsWith("/v1/traces")
                ? otlpBaseOrTracesEndpoint
                : otlpBaseOrTracesEndpoint + "/v1/traces";
    }
}
