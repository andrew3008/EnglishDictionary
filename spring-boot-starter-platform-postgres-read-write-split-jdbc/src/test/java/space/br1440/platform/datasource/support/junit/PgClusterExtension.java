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
import org.testcontainers.containers.ExecConfig;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.output.Slf4jLogConsumer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import org.awaitility.Awaitility;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * JUnit 5 Extension: запускает PostgreSQL-кластер из двух нод (primary + streaming replica)
 * на единой Docker {@link Network}.
 *
 * <h3>Reuse / isolation</h3>
 * <ul>
 *   <li><b>SHARED</b> (default) — один ref-counted кластер на JVM для всех «обычных» IT;
 *       контейнеры помечены {@code withReuse(true)} и переживают повторные Gradle-прогоны
 *       при {@code testcontainers.reuse.enable=true}.</li>
 *   <li><b>ISOLATED</b> — отдельный кластер на тест-класс ({@link PgClusterScope.Mode#ISOLATED}
 *       или пакет {@code *.it.chaos.*}); {@code withReuse(false)}, полный stop в {@code @AfterAll}.
 *       Нужен для {@code pg_promote}, Toxiproxy и прочих topology/network мутаций.</li>
 * </ul>
 */
public class PgClusterExtension implements BeforeAllCallback, AfterAllCallback, ParameterResolver {

    private static final Logger LOG = LoggerFactory.getLogger(PgClusterExtension.class);
    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse("postgres:16-alpine");
    private static final String DB_NAME = "cqrs_routing_test";
    private static final String DB_USER = "test_app";
    private static final String DB_PASSWORD = "test_app";
    private static final String REPL_USER = "replicator";
    private static final String REPL_PASSWORD = "replicator";

    /** Стабильный reuse-hash для shared primary+replica (Testcontainers Ryuk). */
    private static final String SHARED_PRIMARY_REUSE_HASH = "platform-pg-cqrs-primary-v1";
    private static final String SHARED_REPLICA_REUSE_HASH = "platform-pg-cqrs-replica-v1";

    private static final ExtensionContext.Namespace NAMESPACE =
            ExtensionContext.Namespace.create(PgClusterExtension.class);
    private static final String STORE_CLUSTER = "pg-cluster";
    private static final String STORE_NETWORK = "pg-network";
    private static final String STORE_SCOPE = "pg-cluster-scope";

    private static final Object SHARED_LOCK = new Object();
    private static PgCluster sharedCluster;
    private static Network sharedNetwork;
    private static final AtomicInteger sharedRefCount = new AtomicInteger();

    @Override
    public void beforeAll(ExtensionContext context) {
        PgClusterScope.Mode scope = resolveScope(context);
        context.getStore(NAMESPACE).put(STORE_SCOPE, scope);

        if (scope == PgClusterScope.Mode.ISOLATED) {
            startIsolatedCluster(context);
            return;
        }

        acquireSharedCluster(context);
    }

    @Override
    public void afterAll(ExtensionContext context) {
        PgClusterScope.Mode scope = context.getStore(NAMESPACE)
                .remove(STORE_SCOPE, PgClusterScope.Mode.class);
        if (scope == null) {
            return;
        }

        if (scope == PgClusterScope.Mode.ISOLATED) {
            PgCluster cluster = context.getStore(NAMESPACE).remove(STORE_CLUSTER, PgCluster.class);
            Network network = context.getStore(NAMESPACE).remove(STORE_NETWORK, Network.class);
            if (cluster != null) {
                cluster.close(true);
            }
            if (network != null) {
                network.close();
            }
            return;
        }

        releaseSharedCluster(context);
    }

    private static void startIsolatedCluster(ExtensionContext context) {
        Network network = Network.newNetwork();
        context.getStore(NAMESPACE).put(STORE_NETWORK, network);

        PostgreSQLContainer<?> primary = startPrimary(network, false, null);
        prepareReplication(primary);
        GenericContainer<?> replica = startReplica(network, false, null);

        PgCluster cluster = new PgCluster(primary, replica, network);
        awaitReplicaInRecovery(cluster);
        context.getStore(NAMESPACE).put(STORE_CLUSTER, cluster);
        LOG.info("PgCluster started (ISOLATED) for {}: primary={}:{} replica={}:{}",
                context.getRequiredTestClass().getSimpleName(),
                primary.getHost(), primary.getFirstMappedPort(),
                replica.getHost(), replica.getMappedPort(5432));
    }

    private static void acquireSharedCluster(ExtensionContext context) {
        synchronized (SHARED_LOCK) {
            if (sharedCluster == null) {
                sharedNetwork = Network.newNetwork();
                sharedCluster = bootstrapSharedCluster(sharedNetwork);
                LOG.info("PgCluster started (SHARED): primary={}:{} replica={}:{}",
                        sharedCluster.primary().getHost(), sharedCluster.primary().getFirstMappedPort(),
                        sharedCluster.replica().getHost(), sharedCluster.replica().getMappedPort(5432));
            }
            sharedRefCount.incrementAndGet();
            context.getStore(NAMESPACE).put(STORE_CLUSTER, sharedCluster);
            context.getStore(NAMESPACE).put(STORE_NETWORK, sharedNetwork);
        }
    }

    /**
     * Поднимает shared-кластер с reuse; если Docker вернул «битую» топологию (replica не в recovery),
     * один раз пересоздаёт кластер без reuse.
     */
    private static PgCluster bootstrapSharedCluster(Network network) {
        PgCluster cluster = materializeCluster(network, true);
        try {
            awaitReplicaInRecovery(cluster);
            return cluster;
        } catch (AssertionError ex) {
            LOG.warn("SHARED cluster replica not in recovery after reuse bootstrap — recycling fresh: {}",
                    ex.getMessage());
            cluster.close(true);
            return materializeCluster(network, false);
        }
    }

    private static PgCluster materializeCluster(Network network, boolean reusable) {
        String primaryHash = reusable ? SHARED_PRIMARY_REUSE_HASH : null;
        String replicaHash = reusable ? SHARED_REPLICA_REUSE_HASH : null;
        PostgreSQLContainer<?> primary = startPrimary(network, reusable, primaryHash);
        prepareReplication(primary);
        GenericContainer<?> replica = startReplica(network, reusable, replicaHash);
        PgCluster cluster = new PgCluster(primary, replica, network);
        awaitReplicaInRecovery(cluster);
        return cluster;
    }

    private static void releaseSharedCluster(ExtensionContext context) {
        context.getStore(NAMESPACE).remove(STORE_CLUSTER);
        context.getStore(NAMESPACE).remove(STORE_NETWORK);

        synchronized (SHARED_LOCK) {
            int remaining = sharedRefCount.decrementAndGet();
            if (remaining > 0) {
                LOG.debug("PgCluster SHARED lease released, {} consumer(s) remain", remaining);
                return;
            }
            if (remaining < 0) {
                LOG.warn("PgCluster SHARED ref-count underflow ({})", remaining);
                sharedRefCount.set(0);
            }
            // Контейнеры с withReuse(true) остаются для следующего Gradle-прогона.
            LOG.info("PgCluster SHARED last consumer released — containers kept for Testcontainers reuse");
        }
    }

    private static PgClusterScope.Mode resolveScope(ExtensionContext context) {
        return context.getRequiredTestClass()
                .getAnnotation(PgClusterScope.class) != null
                ? context.getRequiredTestClass().getAnnotation(PgClusterScope.class).value()
                : defaultScopeFor(context.getRequiredTestClass());
    }

    /** Chaos-пакет всегда isolated, даже без явной аннотации. */
    static PgClusterScope.Mode defaultScopeFor(Class<?> testClass) {
        if (testClass.getPackageName().contains(".it.chaos")) {
            return PgClusterScope.Mode.ISOLATED;
        }
        return PgClusterScope.Mode.SHARED;
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

    private static PostgreSQLContainer<?> startPrimary(Network network, boolean reusable, String reuseHash) {
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
        applyReuse(primary, reusable, reuseHash);
        primary.start();
        return primary;
    }

    private static GenericContainer<?> startReplica(Network network, boolean reusable, String reuseHash) {
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
        applyReuse(replica, reusable, reuseHash);
        replica.start();
        return replica;
    }

    private static void applyReuse(GenericContainer<?> container, boolean reusable, String reuseHash) {
        if (!reusable) {
            container.withReuse(false);
            return;
        }
        container.withReuse(true);
        if (reuseHash != null) {
            container.withCreateContainerCmdModifier(cmd -> {
                Map<String, String> labels = new HashMap<>();
                if (cmd.getLabels() != null) {
                    labels.putAll(cmd.getLabels());
                }
                labels.put("org.testcontainers.reuse.hash", reuseHash);
                cmd.withLabels(labels);
            });
        }
    }

    private static void prepareReplication(PostgreSQLContainer<?> primary) {
        execAsUser(primary, "postgres",
                "sh", "-c",
                "HBA=\"$PGDATA/pg_hba.conf\"; "
                        + "grep -q 'hostnossl replication " + REPL_USER + "' \"$HBA\" || "
                        + "{ "
                        + "  printf '%s\\n' "
                        + "    'hostnossl replication " + REPL_USER + " all trust' "
                        + "    'host replication " + REPL_USER + " all scram-sha-256' "
                        + "    >> \"$HBA\"; "
                        + "}"
        );
        execAsPostgresOsUserStrict(primary,
                "psql", "-U", DB_USER, "-d", DB_NAME,
                "-c", "DO $$ BEGIN IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='" + REPL_USER + "') THEN "
                        + "CREATE ROLE " + REPL_USER + " WITH REPLICATION LOGIN PASSWORD '" + REPL_PASSWORD + "'; "
                        + "END IF; END $$;"
        );
        execAsPostgresOsUserStrict(primary,
                "psql", "-U", DB_USER, "-d", DB_NAME,
                "-c", "SELECT pg_reload_conf();"
        );
        verifyReplicationHba(primary);
    }

    private static void verifyReplicationHba(PostgreSQLContainer<?> primary) {
        try {
            ExecResult result = primary.execInContainer(
                    ExecConfig.builder()
                            .user("postgres")
                            .command(new String[]{
                                    "sh", "-c",
                                    "grep 'replication " + REPL_USER + "' \"$PGDATA/pg_hba.conf\""
                            })
                            .build());
            if (result.getExitCode() != 0 || result.getStdout().isBlank()) {
                throw new IllegalStateException(
                        "pg_hba.conf has no replication rule for " + REPL_USER + ": stderr=" + result.getStderr());
            }
            LOG.info("pg_hba replication rules: {}", result.getStdout().trim());
        } catch (Exception e) {
            throw new IllegalStateException("Failed to verify pg_hba replication rules", e);
        }
    }

    private static void awaitReplicaInRecovery(PgCluster cluster) {
        Awaitility.await()
                .pollInterval(Duration.ofMillis(300))
                .atMost(Duration.ofMinutes(2))
                .untilAsserted(() -> {
                    try {
                        assertThat(cluster.replicaIsInRecovery()).isTrue();
                    } catch (Exception e) {
                        throw new AssertionError("replica not in recovery yet", e);
                    }
                });
    }

    private static void execAsPostgresOsUserStrict(PostgreSQLContainer<?> container, String... cmd) {
        try {
            ExecResult result = container.execInContainer(
                    ExecConfig.builder()
                            .user("postgres")
                            .command(cmd)
                            .build());
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

        public boolean replicaIsInRecovery() throws Exception {
            ExecResult result = replica.execInContainer(
                    ExecConfig.builder()
                            .user("postgres")
                            .command(new String[]{
                                    "psql", "-U", username(), "-d", dbName(), "-tAc", "SELECT pg_is_in_recovery()"
                            })
                            .build());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("replica pg_is_in_recovery check failed: " + result.getStderr());
            }
            return "t".equalsIgnoreCase(result.getStdout().trim());
        }

        public void execPsql(String sql) throws Exception {
            ExecResult result = primary.execInContainer(
                    ExecConfig.builder()
                            .user("postgres")
                            .command(new String[]{
                                    "psql", "-U", DB_USER, "-d", DB_NAME, "-c", sql
                            })
                            .build());
            if (result.getExitCode() != 0) {
                throw new IllegalStateException("psql failed: " + result.getStderr());
            }
        }

        /** @param stopContainers {@code false} для shared reuse — контейнеры не останавливаются. */
        public void close(boolean stopContainers) {
            if (!stopContainers) {
                return;
            }
            try { replica.stop(); } catch (Exception ignored) { /* shutdown */ }
            try { primary.stop(); } catch (Exception ignored) { /* shutdown */ }
        }

        @Override
        public void close() {
            close(true);
        }
    }
}
