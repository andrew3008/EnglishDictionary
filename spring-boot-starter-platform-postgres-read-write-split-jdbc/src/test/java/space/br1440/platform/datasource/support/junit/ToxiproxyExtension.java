package space.br1440.platform.datasource.support.junit;

import eu.rekawek.toxiproxy.Proxy;
import eu.rekawek.toxiproxy.ToxiproxyClient;
import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.ToxiproxyContainer;
import org.testcontainers.utility.DockerImageName;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * JUnit 5 Extension: запускает один Toxiproxy-контейнер на тест-класс и предоставляет
 * {@link ToxiproxyClient} как параметр.
 * <p>
 * Контейнер запускается на той же {@link Network}, что и PostgreSQL-кластер
 * ({@link PgClusterExtension#network(ExtensionContext)}), что позволяет создавать
 * проксированные L4-туннели до primary/replica и инжектировать сетевые сбои
 * (toxics: latency, bandwidth, slicer, reset_peer, timeout).
 */
public class ToxiproxyExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final DockerImageName TOXIPROXY_IMAGE =
            DockerImageName.parse("ghcr.io/shopify/toxiproxy:2.9.0");

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(ToxiproxyExtension.class);
    private static final String STORE_CONTAINER = "toxiproxy-container";
    private static final String STORE_CLIENT = "toxiproxy-client";

    @Override
    public void beforeAll(ExtensionContext context) {
        Network network = PgClusterExtension.network(context);
        ToxiproxyContainer container = new ToxiproxyContainer(TOXIPROXY_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("toxiproxy")
                .withReuse(false);
        container.start();

        ToxiproxyClient client = new ToxiproxyClient(container.getHost(), container.getControlPort());
        context.getStore(NAMESPACE).put(STORE_CONTAINER, container);
        context.getStore(NAMESPACE).put(STORE_CLIENT, client);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        ToxiproxyContainer container =
                context.getStore(NAMESPACE).remove(STORE_CONTAINER, ToxiproxyContainer.class);
        if (container != null && container.isRunning()) {
            container.stop();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == ToxiproxyClient.class || type == ToxiproxyContainer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == ToxiproxyClient.class) {
            return extensionContext.getStore(NAMESPACE).get(STORE_CLIENT, ToxiproxyClient.class);
        }
        if (type == ToxiproxyContainer.class) {
            return extensionContext.getStore(NAMESPACE).get(STORE_CONTAINER, ToxiproxyContainer.class);
        }
        throw new ParameterResolutionException("Unsupported parameter: " + parameterContext.getParameter());
    }

    /**
     * Создаёт L4 проксирующий туннель Toxiproxy → upstream.
     * Возвращает {@link Proxy}, который тест использует для добавления toxics
     * (latency, reset_peer, ...) и для построения JDBC URL через
     * {@link #toxicJdbcHost(ToxiproxyContainer, Proxy)}.
     *
     * @param client          Toxiproxy admin client
     * @param name            уникальное имя прокси (например, {@code "primary"})
     * @param listenPort      контейнерный порт, на котором Toxiproxy будет слушать
     *                        (должен совпадать с {@link ToxiproxyContainer#getMappedPort(int)} от
     *                        {@code PROXY_PORT_RANGE})
     * @param upstreamAlias   network alias upstream-контейнера (например, {@code "pg-primary"})
     * @param upstreamPort    upstream-порт (обычно {@code 5432})
     */
    public static Proxy createProxy(ToxiproxyClient client,
                                    String name,
                                    int listenPort,
                                    String upstreamAlias,
                                    int upstreamPort) {
        try {
            return client.createProxy(name, "0.0.0.0:" + listenPort, upstreamAlias + ":" + upstreamPort);
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to create Toxiproxy proxy " + name, e);
        }
    }

    /**
     * Возвращает host:port (mapped) для подключения JDBC через Toxiproxy.
     */
    public static String toxicJdbcHost(ToxiproxyContainer container, Proxy proxy) {
        // ToxiproxyContainer экспозирует диапазон портов 8666-8697; mapped-порт берём
        // из listen address proxy ("0.0.0.0:N").
        String listen = proxy.getListen();
        int port = Integer.parseInt(listen.substring(listen.lastIndexOf(':') + 1));
        Integer mapped = container.getMappedPort(port);
        return container.getHost() + ":" + mapped;
    }
}
