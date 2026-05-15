package space.br1440.platform.datasource.support.junit;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.BeforeAllCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.testcontainers.containers.Container.ExecResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;

/**
 * JUnit 5 Extension: запускает PostgreSQL-кластер из двух нод (primary + streaming replica)
 * на единой Docker {@link Network}.
 * <p>
 * <h3>Что включено</h3>
 * <ul>
 *   <li>{@code pg-primary} — PostgreSQL primary с настроенной WAL replication ({@code wal_level=replica},
 *       {@code max_wal_senders=10}) и репликационным пользователем {@code replicator}.</li>
 *   <li>{@code pg-replica} — клонируется из primary через {@code pg_basebackup -R} и стартует
 *       в hot-standby режиме (read-only).</li>
 *   <li>Общая {@link Network} — обе ноды в одной сети, что позволяет {@link ToxiproxyExtension}
 *       проксировать L4-трафик к ним.</li>
 * </ul>
 * <p>
 * <h3>Производительность</h3>
 * Контейнеры запускаются один раз на {@code @TestClass} ({@link BeforeAllCallback}), так как
 * полный кластер с {@code pg_basebackup} занимает ~10–15 секунд. Тесты должны быть
 * <b>идемпотентными</b> (использовать уникальные таблицы / {@code TRUNCATE} в {@code @BeforeEach}).
 */
public class PgClusterExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PgClusterExtension.class);
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
    private static final String DB_NAME = "cqrs_routing_test";
    private static final String DB_USER = "test_app";
    private static final String DB_PASSWORD = "test_app";
    private static final String REPL_USER = "replicator";
    private static final String REPL_PASSWORD = "replicator";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(PgClusterExtension.class);
    private static final String STORE_CLUSTER = "pg-cluster";
    private static final String STORE_NETWORK = "pg-network";

    @Override
    public void beforeAll(ExtensionContext context) {
        Network network = Network.newNetwork();
        context.getStore(NAMESPACE).put(STORE_NETWORK, network);

        PostgreSQLContainer<?> primary = startPrimary(network);
        prepareReplication(primary);
        GenericContainer<?> replica = startReplica(network);

        PgCluster cluster = new PgCluster(primary, replica, network);
        context.getStore(NAMESPACE).put(STORE_CLUSTER, cluster);
        LOG.info("PgCluster started: primary={}:{} replica={}:{}",
                primary.getHost(), primary.getFirstMappedPort(),
                replica.getHost(), replica.getMappedPort(5432));
    }

    @Override
    public void afterAll(ExtensionContext context) {
        PgCluster cluster = context.getStore(NAMESPACE).remove(STORE_CLUSTER, PgCluster.class);
        if (cluster != null) {
            cluster.close();
        }
        Network network = context.getStore(NAMESPACE).remove(STORE_NETWORK, Network.class);
        if (network != null) {
            network.close();
        }
    }

    @Override
    public boolean supportsParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        return type == PgCluster.class || type == Network.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameterContext, ExtensionContext extensionContext)
            throws ParameterResolutionException {
        Class<?> type = parameterContext.getParameter().getType();
        if (type == PgCluster.class) {
            return extensionContext.getStore(NAMESPACE).get(STORE_CLUSTER, PgCluster.class);
        }
        if (type == Network.class) {
            return extensionContext.getStore(NAMESPACE).get(STORE_NETWORK, Network.class);
        }
        throw new ParameterResolutionException("Unsupported: " + parameterContext.getParameter());
    }

    public static Network network(ExtensionContext context) {
        Network net = context.getStore(NAMESPACE).get(STORE_NETWORK, Network.class);
        if (net == null) {
            throw new IllegalStateException(
                    "PgClusterExtension must be registered before ToxiproxyExtension");
        }
        return net;
    }

    private static PostgreSQLContainer<?> startPrimary(Network network) {
        @SuppressWarnings("resource")
        PostgreSQLContainer<?> primary = new PostgreSQLContainer<>(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("pg-primary")
                .withDatabaseName(DB_NAME)
                .withUsername(DB_USER)
                .withPassword(DB_PASSWORD)
                .withCommand(
                        "postgres",
                        "-c", "wal_level=replica",
                        "-c", "max_wal_senders=10",
                        "-c", "max_replication_slots=10",
                        "-c", "hot_standby=on",
                        "-c", "listen_addresses=*"
                )
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("pg-primary"));
        primary.start();
        return primary;
    }

    private static void prepareReplication(PostgreSQLContainer<?> primary) {
        // pg_hba.conf — разрешаем replication-подключения от replica
        execAsRoot(primary,
                "sh", "-c",
                "echo 'host replication " + REPL_USER + " 0.0.0.0/0 md5' >> /var/lib/postgresql/data/pg_hba.conf"
        );
        execAsUser(primary, "postgres",
                "psql", "-U", "postgres", "-d", DB_NAME,
                "-c", "CREATE ROLE " + REPL_USER + " WITH REPLICATION LOGIN PASSWORD '" + REPL_PASSWORD + "';"
        );
        execAsUser(primary, "postgres",
                "psql", "-U", "postgres", "-d", DB_NAME,
                "-c", "SELECT pg_reload_conf();"
        );
    }

    private static GenericContainer<?> startReplica(Network network) {
        // Inline shell-script: bootstrap-данные через pg_basebackup при первом старте
        // (когда PGDATA пустой), затем стартует postgres в hot-standby.
        String entrypoint =
                "#!/bin/sh\n" +
                        "set -e\n" +
                        "if [ -z \"$(ls -A /var/lib/postgresql/data 2>/dev/null)\" ]; then\n" +
                        "  echo 'Replica: bootstrapping via pg_basebackup from pg-primary' >&2\n" +
                        "  PGPASSWORD=" + REPL_PASSWORD + " pg_basebackup \\\n" +
                        "    -h pg-primary -p 5432 -U " + REPL_USER + " \\\n" +
                        "    -D /var/lib/postgresql/data -X stream -P -R \\\n" +
                        "    --slot=replica_slot --create-slot --no-password\n" +
                        "  chown -R postgres:postgres /var/lib/postgresql/data\n" +
                        "  chmod 0700 /var/lib/postgresql/data\n" +
                        "fi\n" +
                        "exec su-exec postgres postgres \\\n" +
                        "  -c hot_standby=on \\\n" +
                        "  -c listen_addresses='*'\n";

        @SuppressWarnings("resource")
        GenericContainer<?> replica = new GenericContainer<>(POSTGRES_IMAGE)
                .withNetwork(network)
                .withNetworkAliases("pg-replica")
                .withExposedPorts(5432)
                .withCopyToContainer(
                        org.testcontainers.images.builder.Transferable.of(entrypoint, 0755),
                        "/replica-entrypoint.sh"
                )
                .withCommand("sh", "/replica-entrypoint.sh")
                .withLogConsumer(new Slf4jLogConsumer(LOG).withPrefix("pg-replica"))
                .waitingFor(Wait.forLogMessage(
                                ".*database system is ready to accept read[- ]only connections.*\\s", 1)
                        .withStartupTimeout(Duration.ofMinutes(3)));
        replica.start();
        return replica;
    }

    private static void execAsRoot(PostgreSQLContainer<?> container, String... cmd) {
        try {
            ExecResult result = container.execInContainer(cmd);
            if (result.getExitCode() != 0) {
                throw new IllegalStateException(
                        "Container exec failed: " + String.join(" ", cmd) + " stderr=" + result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("Container exec error", e);
        }
    }

    private static void execAsUser(PostgreSQLContainer<?> container, String user, String... cmd) {
        try {
            ExecResult result = container.execInContainer(
                    org.testcontainers.containers.ExecConfig.builder()
                            .user(user)
                            .command(cmd)
                            .build());
            if (result.getExitCode() != 0) {
                LOG.warn("psql exec returned {}: stderr={}", result.getExitCode(), result.getStderr());
            }
        } catch (Exception e) {
            throw new IllegalStateException("psql exec error", e);
        }
    }

    public static final class PgCluster implements AutoCloseable {

        private final PostgreSQLContainer<?> primary;
        private final GenericContainer<?> replica;
        private final Network network;

        PgCluster(PostgreSQLContainer<?> primary, GenericContainer<?> replica, Network network) {
            this.primary = primary;
            this.replica = replica;
            this.network = network;
        }

        public PostgreSQLContainer<?> primary() { return primary; }
        public GenericContainer<?> replica() { return replica; }
        public Network network() { return network; }

        public String dbName() { return DB_NAME; }
        public String username() { return DB_USER; }
        public String password() { return DB_PASSWORD; }
        public String replicationUsername() { return REPL_USER; }
        public String replicationPassword() { return REPL_PASSWORD; }

        public String pgNodesString() {
            return primary.getHost() + ":" + primary.getFirstMappedPort()
                    + "," + replica.getHost() + ":" + replica.getMappedPort(5432);
        }

        public String primaryHostPort() {
            return primary.getHost() + ":" + primary.getFirstMappedPort();
        }

        public String replicaHostPort() {
            return replica.getHost() + ":" + replica.getMappedPort(5432);
        }

        public String primaryAlias() { return "pg-primary"; }
        public String replicaAlias() { return "pg-replica"; }

        @Override
        public void close() {
            try { replica.stop(); } catch (Exception ignored) { /* shutdown */ }
            try { primary.stop(); } catch (Exception ignored) { /* shutdown */ }
        }
    }
}
