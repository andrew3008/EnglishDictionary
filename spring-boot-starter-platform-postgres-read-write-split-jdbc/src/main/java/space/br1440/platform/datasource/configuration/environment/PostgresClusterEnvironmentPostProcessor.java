package space.br1440.platform.datasource.configuration.environment;

import org.apache.commons.logging.Log;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.boot.logging.DeferredLogFactory;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.util.StringUtils;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.exception.PostgresClusterConfigurationException;

import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * <p>EPP <b>не</b> является вторым «выключателем» стартера. Единственный activation gate —
 * {@link OnPgTopologyDirectCondition} (Single Source of Truth).
 * EPP выполняет две независимые задачи:
 * <ol>
 *   <li><b>Default property injection.</b> Регистрирует {@link MapPropertySource} с самым
 *       низким приоритетом ({@code propertySources.addLast(...)}), мапящий {@code PG_*}
 *       environment variables в канонические {@code platform.datasource.postgres-cluster.*}
 *       ключи. Это даёт zero-configuration: разработчик не пишет ни одной строки
 *       {@code platform.datasource.postgres-cluster.*} в собственном {@code application.yml},
 *       и одновременно получает обычный Spring Boot relaxed-binding над ENV-переменными.</li>
 *   <li><b>Early fail-fast precondition check.</b> При {@code PG_TOPOLOGY=DIRECT} проверяет
 *       наличие непустой {@code PG_CLUSTER_NODES} <i>до</i> того, как Spring построит
 *       {@code @ConfigurationProperties}-биндинг. Это даёт человеко-читаемую ошибку с
 *       шагом исправления в Kubernetes-логах вместо невнятного {@code BindException},
 *       приходящего на этапе создания {@code @Bean} стартера.</li>
 * </ol>
 *
 * <h2>Дизайн-решение: {@code PG_CLUSTER_NODES} fail-fast vs {@code application.yml}</h2>
 * <p>EPP выполняется <i>раньше</i> загрузки {@code application.yml}, поэтому fail-fast
 * сработает, даже если разработчик задал {@code platform.datasource.postgres-cluster.nodes}
 * в собственном yml вместо {@code PG_CLUSTER_NODES}. Это <b>намеренное</b> поведение:
 * {@code PG_CLUSTER_NODES} — это infrastructure-level config (Kubernetes ConfigMap/Helm
 * values), а не application-level config (бизнес-настройки в yml). Twelve-Factor App III
 * запрещает хранить инфраструктурные параметры в кодовой базе. Если потребителю нужно
 * переопределить ноды кластера прицельно (например, для интеграционного теста), ему
 * следует выставить {@code PG_CLUSTER_NODES} в тестовом сценарии — а не пытаться
 * «обойти» EPP через yml.
 */
public final class PostgresClusterEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

    public static final String PROPERTY_SOURCE_NAME = "postgresClusterEnvDefaults";

    static final String ENV_NODES = "PG_CLUSTER_NODES";
    static final String ENV_DATABASE = "PG_DATABASE";
    static final String ENV_USERNAME = "PG_USERNAME";
    static final String ENV_PASSWORD = "PG_PASSWORD";
    static final String ENV_CONNECTION_PROPERTIES = "PG_CONNECTION_PROPERTIES";

    static final String PREFIX = PostgresClusterDataSourceProperties.PREFIX;

    private static final Set<String> RESERVED_CONNECTION_PROPERTY_KEYS = Set.of("user", "password");

    static final String MSG_NODES_MISSING = """
            Environment variable PG_CLUSTER_NODES is missing or blank: cannot resolve PostgreSQL cluster nodes.\
            """.stripIndent();

    static final String ACTION_NODES_MISSING = """
            Set the PG_CLUSTER_NODES environment variable in your Kubernetes ConfigMap or Helm values
            to a non-empty CSV list of host:port pairs, e.g.:
              PG_CLUSTER_NODES=10.116.37.137:5432,10.116.37.138:5432\
            """.stripIndent();

    private final Log logger;

    public PostgresClusterEnvironmentPostProcessor(DeferredLogFactory logFactory) {
        this.logger = logFactory.getLog(PostgresClusterEnvironmentPostProcessor.class);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String topology = environment.getProperty(OnPgTopologyDirectCondition.ENV_VAR);
        if (!OnPgTopologyDirectCondition.DIRECT.equalsIgnoreCase(topology)) {
            if (logger.isDebugEnabled()) {
                logger.debug("[postgres-cluster] EPP skipped: " + OnPgTopologyDirectCondition.ENV_VAR
                        + (topology == null ? " is not set" : "=" + topology + " (expected DIRECT)"));
            }

            return;
        }

        String nodesRaw = environment.getProperty(ENV_NODES);
        if (!StringUtils.hasText(nodesRaw)) {
            throw new PostgresClusterConfigurationException(MSG_NODES_MISSING, ACTION_NODES_MISSING);
        }

        Map<String, Object> defaults = buildDefaults(environment, nodesRaw);
        environment.getPropertySources().addLast(new MapPropertySource(PROPERTY_SOURCE_NAME, defaults));

        if (logger.isInfoEnabled()) {
            logger.info("[postgres-cluster] EPP injected " + defaults.size()
                    + " default(s) into property source '" + PROPERTY_SOURCE_NAME
                    + "' (lowest precedence) for prefix '" + PREFIX + "'");
        }
    }

    private Map<String, Object> buildDefaults(ConfigurableEnvironment environment, String nodesRaw) {
        Map<String, Object> defaults = new LinkedHashMap<>();

        defaults.put(PREFIX + ".nodes", parseNodes(nodesRaw));

        putIfPresent(defaults, environment, ENV_DATABASE, PREFIX + ".database");
        putIfPresent(defaults, environment, ENV_USERNAME, PREFIX + ".credentials.username");
        putIfPresent(defaults, environment, ENV_PASSWORD, PREFIX + ".credentials.password");

        String connectionPropsRaw = environment.getProperty(ENV_CONNECTION_PROPERTIES);
        if (StringUtils.hasText(connectionPropsRaw)) {
            Map<String, String> parsed = parseConnectionProperties(connectionPropsRaw);
            for (Map.Entry<String, String> e : parsed.entrySet()) {
                defaults.put(PREFIX + ".connection-properties." + e.getKey(), e.getValue());
            }
        }

        return defaults;
    }

    private static List<String> parseNodes(String raw) {
        List<String> result = new LinkedList<>();
        for (String part : raw.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) {
                result.add(trimmed);
            }
        }

        return result;
    }

    private static Map<String, String> parseConnectionProperties(String raw) {
        Map<String, String> result = new LinkedHashMap<>();

        for (String pair : raw.split(";")) {
            String trimmedPair = pair.trim();
            if (trimmedPair.isEmpty()) {
                continue;
            }

            int eq = trimmedPair.indexOf('=');
            if (eq <= 0) {
                continue;
            }

            String key = trimmedPair.substring(0, eq).trim();
            String value = trimmedPair.substring(eq + 1).trim();
            if (key.isEmpty()) {
                continue;
            }

            if (RESERVED_CONNECTION_PROPERTY_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                continue;
            }

            result.put(key, value);
        }

        return result;
    }

    private static void putIfPresent(Map<String, Object> defaults,
                                     ConfigurableEnvironment environment,
                                     String envVar,
                                     String targetKey) {
        String value = environment.getProperty(envVar);
        if (StringUtils.hasText(value)) {
            defaults.put(targetKey, value);
        }
    }
}
