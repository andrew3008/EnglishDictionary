package space.br1440.platform.datasource.configuration.support;

import space.br1440.platform.datasource.exception.PostgresClusterConfigurationException;
import space.br1440.platform.datasource.configuration.PostgresClusterDataSourceProperties;

import java.util.List;

/**
 * Третий, самый узкий уровень defence-in-depth для {@link PostgresClusterDataSourceProperties}.
 *
 * <h2>Три эшелона валидации (от внешнего к внутреннему)</h2>
 * <ol>
 *   <li><b>Early fail-fast в EPP.</b>
 *       {@link space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor} проверяет наличие непустой
 *       {@code PG_CLUSTER_NODES} <i>до</i> загрузки {@code application.yml} и до
 *       создания любых бинов. Сообщение содержит явный шаг исправления для DevOps
 *       («Set the PG_CLUSTER_NODES environment variable in your Kubernetes ConfigMap…»).
 *       <i>Покрывает 99% реальных конфигурационных ошибок.</i></li>
 *   <li><b>JSR-303 биндинг.</b>
 *       {@link PostgresClusterDataSourceProperties} помечен {@code @Validated} с
 *       аннотациями {@code @NotEmpty List<String> nodes} и {@code @NotBlank String database}.
 *       Spring Boot выбрасывает {@code ConfigurationPropertiesBindException} на стадии
 *       создания бина — это ловит сценарии, где разработчик вручную перебивает поле в
 *       {@code application.yml} пустым значением.</li>
 *   <li><b>Этот валидатор.</b>
 *       Защищает специфический <i>programmatic escape-hatch</i>: разработчик переопределяет
 *       {@code @Bean PostgresClusterDataSourceProperties} (для нестандартной read-стратегии
 *       или test-config-а), но забывает заполнить {@code nodes}/{@code database}.
 *       JSR-303 в этом случае <b>не срабатывает</b>, потому что {@code @Validated}-биндинг
 *       применяется только к {@code @ConfigurationProperties}-бинам, создаваемым Spring-ом
 *       через binding-механизм; ручной {@code new PostgresClusterDataSourceProperties()}
 *       внутри пользовательской {@code @Configuration} проходит мимо.</li>
 * </ol>
 */
public final class RoutingStartupValidator {

    public static final String MSG_NODES_MISSING = """
            PostgreSQL cluster nodes are not configured: cannot construct JDBC/R2DBC URL.\
            """.stripIndent();

    public static final String ACTION_NODES_MISSING = """
            This error indicates a programmatic @Bean PostgresClusterDataSourceProperties override
            with an empty 'nodes' field. To resolve:
              (1) Preferred: drop the custom @Bean and rely on the PG_CLUSTER_NODES environment variable, e.g.:
                    PG_CLUSTER_NODES=10.116.37.137:5432,10.116.37.138:5432
              (2) Or, if the custom @Bean is intentional: ensure setNodes(...) is called with a non-empty list
                    of host:port pairs before the @Bean returns.\
            """.stripIndent();

    public static final String MSG_DATABASE_MISSING = """
            PostgreSQL database name is not configured: cannot construct JDBC/R2DBC URL.\
            """.stripIndent();

    public static final String ACTION_DATABASE_MISSING = """
            This error indicates a programmatic @Bean PostgresClusterDataSourceProperties override
            with a blank 'database' field. To resolve:
              (1) Preferred: drop the custom @Bean and rely on the PG_DATABASE environment variable, e.g.:
                    PG_DATABASE=my_service
              (2) Or, if the custom @Bean is intentional: ensure setDatabase(...) is called with a non-blank value
                    before the @Bean returns.\
            """.stripIndent();

    public void validate(PostgresClusterDataSourceProperties props) {
        List<String> nodes = props.getNodes();
        if (nodes == null || nodes.isEmpty()) {
            throw new PostgresClusterConfigurationException(MSG_NODES_MISSING, ACTION_NODES_MISSING);
        }

        String database = props.getDatabase();
        if (database == null || database.isBlank()) {
            throw new PostgresClusterConfigurationException(MSG_DATABASE_MISSING, ACTION_DATABASE_MISSING);
        }
    }
}
