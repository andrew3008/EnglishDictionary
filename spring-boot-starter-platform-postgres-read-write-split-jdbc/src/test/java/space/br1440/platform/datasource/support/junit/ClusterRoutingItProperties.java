package space.br1440.platform.datasource.support.junit;

import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;
import space.br1440.platform.datasource.postgres.internal.bootstrap.PgEnvReader;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Сборка property-контракта для cluster IT на {@link org.springframework.boot.SpringApplication#run()}.
 *
 * <p><b>Почему не {@code PG_*} через {@code setDefaultProperties}.</b> EPP читает {@code PG_*}
 * из {@link org.springframework.core.env.Environment} на фазе {@code EnvironmentPreparedEvent}.
 * Значения из {@code setDefaultProperties} попадают в {@code defaultProperties} MapPropertySource
 * слишком поздно — EPP их не видит, canonical {@code database}/{@code nodes} не инжектируются,
 * контекст падает на {@code PgClusterPropertiesValidator}.
 *
 * <p>Два поддерживаемых пути:
 * <ul>
 *   <li><b>Canonical keys</b> в {@code defaultProperties} (indexed {@code nodes[i].host/port}) —
 *       надёжный путь для большинства cluster IT (аналог {@code @DynamicPropertySource});</li>
 *   <li><b>{@link #wirePgEnvBeforeEpp(SpringApplication, ...)}</b> — имитация K8s env для EPP E2E:
 *       {@code PG_*} кладутся в {@link ConfigurableEnvironment} через {@link SpringApplication#setEnvironment}
 *       до {@code run()}, EPP видит их при {@code postProcessEnvironment}.</li>
 * </ul>
 */
public final class ClusterRoutingItProperties {

    private static final List<String> PG_ENV_KEYS = List.of(
            PgEnvReader.ENV_ROUTING_MODE,
            PgEnvReader.ENV_NODES,
            PgEnvReader.ENV_DATABASE,
            PgEnvReader.ENV_SCHEMA,
            PgEnvReader.ENV_USERNAME,
            PgEnvReader.ENV_PASSWORD,
            PgEnvReader.ENV_CONNECTION_PROPERTIES
    );

    private ClusterRoutingItProperties() {
    }

    public static void applyClientActivation(Map<String, Object> target) {
        target.put(OnClientDataSourceRoutingCondition.PROPERTY, "CLIENT");
    }

    /**
     * Имитирует Kubernetes env vars до {@code SpringApplication.run()} — EPP увидит их
     * через {@code systemProperties} property source.
     */
    public static void installPgEnvSystemProperties(String pgNodesCsv,
                                                    String database,
                                                    String username,
                                                    String password) {
        installPgEnvSystemProperties(pgNodesCsv, database, username, password, null);
    }

    public static void installPgEnvSystemProperties(String pgNodesCsv,
                                                    String database,
                                                    String username,
                                                    String password,
                                                    String connectionProperties) {
        installPgEnvSystemProperties(pgNodesCsv, database, null, username, password, connectionProperties);
    }

    public static void installPgEnvSystemProperties(String pgNodesCsv,
                                                    String database,
                                                    String schema,
                                                    String username,
                                                    String password,
                                                    String connectionProperties) {
        System.setProperty(PgEnvReader.ENV_ROUTING_MODE, "CLIENT");
        System.setProperty(PgEnvReader.ENV_NODES, pgNodesCsv);
        System.setProperty(PgEnvReader.ENV_DATABASE, database);
        if (schema != null) {
            System.setProperty(PgEnvReader.ENV_SCHEMA, schema);
        }
        System.setProperty(PgEnvReader.ENV_USERNAME, username);
        System.setProperty(PgEnvReader.ENV_PASSWORD, password);
        if (connectionProperties != null) {
            System.setProperty(PgEnvReader.ENV_CONNECTION_PROPERTIES, connectionProperties);
        }
    }

    public static void installPgEnvSystemProperties(PgCluster cluster) {
        installPgEnvSystemProperties(
                cluster.pgNodesString(), cluster.dbName(), cluster.username(), cluster.password());
    }

    public static void clearPgEnvSystemProperties() {
        for (String key : PG_ENV_KEYS) {
            System.clearProperty(key);
        }
    }

    /**
     * Предзаполняет {@link SpringApplication} raw {@code PG_*} до EPP.
     * {@code setDefaultProperties} для этого недостаточно — EPP отрабатывает раньше.
     */
    public static void wirePgEnvBeforeEpp(SpringApplication app,
                                          String pgNodesCsv,
                                          String database,
                                          String username,
                                          String password) {
        wirePgEnvBeforeEpp(app, pgNodesCsv, database, username, password, null);
    }

    public static void wirePgEnvBeforeEpp(SpringApplication app,
                                          String pgNodesCsv,
                                          String database,
                                          String username,
                                          String password,
                                          String connectionProperties) {
        app.setEnvironment(environmentWithRawPgEnv(
                pgNodesCsv, database, null, username, password, connectionProperties));
    }

    public static void wirePgEnvBeforeEpp(SpringApplication app,
                                          String pgNodesCsv,
                                          String database,
                                          String schema,
                                          String username,
                                          String password,
                                          String connectionProperties) {
        app.setEnvironment(environmentWithRawPgEnv(
                pgNodesCsv, database, schema, username, password, connectionProperties));
    }

    public static void wirePgEnvBeforeEpp(SpringApplication app, PgCluster cluster) {
        wirePgEnvBeforeEpp(app, cluster.pgNodesString(), cluster.dbName(), cluster.username(), cluster.password());
    }

    public static ConfigurableEnvironment environmentWithRawPgEnv(String pgNodesCsv,
                                                                  String database,
                                                                  String username,
                                                                  String password,
                                                                  String connectionProperties) {
        return environmentWithRawPgEnv(pgNodesCsv, database, null, username, password, connectionProperties);
    }

    public static ConfigurableEnvironment environmentWithRawPgEnv(String pgNodesCsv,
                                                                  String database,
                                                                  String schema,
                                                                  String username,
                                                                  String password,
                                                                  String connectionProperties) {
        ConfigurableEnvironment environment = new StandardEnvironment();
        Map<String, Object> raw = rawPgEnvMap(pgNodesCsv, database, schema, username, password, connectionProperties);
        environment.getPropertySources().addFirst(new MapPropertySource("itSimulatedPgEnv", raw));
        return environment;
    }

    private static Map<String, Object> rawPgEnvMap(String pgNodesCsv,
                                                   String database,
                                                   String schema,
                                                   String username,
                                                   String password,
                                                   String connectionProperties) {
        Map<String, Object> raw = new LinkedHashMap<>();
        raw.put(PgEnvReader.ENV_ROUTING_MODE, "CLIENT");
        raw.put(PgEnvReader.ENV_NODES, pgNodesCsv);
        raw.put(PgEnvReader.ENV_DATABASE, database);
        if (schema != null) {
            raw.put(PgEnvReader.ENV_SCHEMA, schema);
        }
        raw.put(PgEnvReader.ENV_USERNAME, username);
        raw.put(PgEnvReader.ENV_PASSWORD, password);
        if (connectionProperties != null) {
            raw.put(PgEnvReader.ENV_CONNECTION_PROPERTIES, connectionProperties);
        }
        return raw;
    }

    public static void applyCanonicalCluster(Map<String, Object> target, PgCluster cluster) {
        applyCanonicalCluster(
                target, cluster.pgNodesString(), cluster.dbName(), cluster.username(), cluster.password());
    }

    public static void applyCanonicalCluster(Map<String, Object> target,
                                             String pgNodesCsv,
                                             String database,
                                             String username,
                                             String password) {
        applyIndexedNodes(target, pgNodesCsv);
        target.put(PgClusterProperties.PREFIX + ".database", database);
        target.put(PgClusterProperties.PREFIX + ".credentials.username", username);
        target.put(PgClusterProperties.PREFIX + ".credentials.password", password);
    }

    public static void applyIndexedNodes(Map<String, Object> target, String pgNodesCsv) {
        String[] entries = pgNodesCsv.split(",");
        for (int i = 0; i < entries.length; i++) {
            String[] hostPort = entries[i].trim().split(":");
            target.put(PgClusterProperties.PREFIX + ".nodes[" + i + "].host", hostPort[0].trim());
            target.put(PgClusterProperties.PREFIX + ".nodes[" + i + "].port", Integer.parseInt(hostPort[1].trim()));
        }
    }

    public static void applyDefaultPoolTuning(Map<String, Object> target) {
        target.put(PgClusterProperties.PREFIX + ".pool.write.maximum-pool-size", "5");
        target.put(PgClusterProperties.PREFIX + ".pool.read.maximum-pool-size", "5");
        target.put(PgClusterProperties.PREFIX + ".pool.write.connection-timeout", "5000");
        target.put(PgClusterProperties.PREFIX + ".pool.read.connection-timeout", "5000");
    }

    /** Базовый набор для cluster IT: spring tuning + CLIENT + canonical cluster + pool defaults. */
    public static Map<String, Object> baseClusterContextProperties(PgCluster cluster) {
        Map<String, Object> props = new HashMap<>();
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put("logging.level.space.br1440.platform.datasource", "DEBUG");
        applyClientActivation(props);
        applyCanonicalCluster(props, cluster);
        applyDefaultPoolTuning(props);
        return props;
    }
}
