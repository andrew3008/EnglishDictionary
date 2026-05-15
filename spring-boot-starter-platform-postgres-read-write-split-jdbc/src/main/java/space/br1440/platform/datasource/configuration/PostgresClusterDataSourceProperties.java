package space.br1440.platform.datasource.configuration;

import com.fasterxml.jackson.annotation.JsonIgnore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import lombok.Getter;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * {@code @ConfigurationProperties}-класс с агрегированными свойствами подключения к
 * PostgreSQL-кластеру (primary + read-replicas) и параметрами read-пула (стратегия чтения).
 *
 * <h2>Контракт активации</h2>
 * <p>Класс активен только при environment-переменной {@code PG_TOPOLOGY=DIRECT}
 * (см. {@link OnPgTopologyDirectCondition}).
 * В режимах {@code PG_TOPOLOGY=HAPROXY} или при отсутствии переменной auto-configuration
 * стартеров не срабатывает, бины не создаются, и микросервис идёт по канонической
 * Spring Boot {@code DataSourceAutoConfiguration} через {@code spring.datasource.url=${POSTGRES_URL}}.
 *
 * <h2>Источники свойств — три приоритета (от низшего к высшему)</h2>
 * <ol>
 *   <li><b>ENV-дефолты, инжектируемые стартером</b> — {@link PostgresClusterEnvironmentPostProcessor}
 *       читает {@code PG_CLUSTER_NODES}, {@code PG_DATABASE}, {@code PG_USERNAME},
 *       {@code PG_PASSWORD}, {@code PG_CONNECTION_PROPERTIES} (properties-style {@code k=v;k=v})
 *       и регистрирует {@code MapPropertySource} с самым низким приоритетом
 *       ({@code propertySources.addLast(...)}). Это даёт zero-configuration: разработчик
 *       микросервиса не пишет ни одного {@code platform.datasource.postgres-cluster.*}
 *       ключа в собственном {@code application.yml}.</li>
 *   <li><b>{@code application.yml} потребителя</b> — стандартный Spring Boot binding из
 *       прикладного yml/properties-файла. Он перебивает ENV-дефолты, поскольку Spring
 *       помещает приложенческий {@code applicationConfig} property-source выше
 *       {@code MapPropertySource}-а нашего EPP.</li>
 *   <li><b>OS environment / system properties</b> — стандартный Spring relaxed binding
 *       (например, {@code PLATFORM_DATASOURCE_POSTGRES_CLUSTER_DATABASE} в Kubernetes).
 *       Этот слой имеет ещё более высокий приоритет, чем {@code application.yml}.</li>
 * </ol>
 *
 * <p>Таким образом контракт со стороны DevOps остаётся прежним — конфигурация задаётся
 * через {@code PG_*} environment variables в Kubernetes ConfigMap/Secret. А разработчик
 * микросервиса при необходимости может тонко переопределить любое поле в собственном
 * {@code application.yml} (например, стратегию чтения).
 *
 * <h2>Schema (PostgreSQL {@code search_path})</h2>
 * <p>В DTO нет поля схемы намеренно: схема — это контракт ORM/SQL-слоя, а не
 * datasource-инфраструктуры. Стартер отвечает только за топологию кластера, маршрутизацию
 * write/read и пулы соединений; что именно читает и пишет приложение — за пределами
 * его ответственности (SRP).
 *
 * <p>Схема задаётся в ORM-слое потребителя:
 * <ul>
 *   <li>JPA/Hibernate — {@code spring.jpa.properties.hibernate.default_schema};</li>
 *   <li>Spring Data JDBC/JdbcTemplate — квалифицированные имена ({@code schema.table})
 *       либо явный {@code SET search_path} в начале сессии;</li>
 *   <li>R2DBC/Spring Data R2DBC — аналогично: квалификация имён или
 *       {@code Mono.from(connection.createStatement("SET search_path TO ...").execute())}.</li>
 * </ul>
 *
 * <p>JDBC/R2DBC URL формируется без параметра {@code currentSchema}; PostgreSQL применяет
 * серверный дефолт ({@code "$user", public}). Если приложению нужна другая схема, оно
 * выставляет её средствами своего ORM-слоя.
 */
@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = PostgresClusterDataSourceProperties.PREFIX)
public class PostgresClusterDataSourceProperties {

    public static final String PREFIX = "platform.datasource.postgres-cluster";

    /**
     * Список узлов PostgreSQL-кластера в формате {@code host:port} (primary + read-replicas).
     * <p>
     * Источник — environment variable {@code PG_CLUSTER_NODES} (CSV-форма), читаемая
     * {@link space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor}-ом и инжектируемая в виде
     * {@code platform.datasource.postgres-cluster.nodes}. Потребитель может переопределить
     * список в собственном {@code application.yml}, но в типовом zero-config-сценарии
     * этого не делает.
     * <p>
     * JSR-303: список не должен быть пустым, иначе невозможно построить multi-host JDBC/R2DBC
     * URL. Если поле остаётся пустым из-за того, что {@code PG_CLUSTER_NODES} не задана,
     * до JSR-303 не дойдёт — раньше сработает early fail-fast в EPP с понятным «что делать» сообщением.
     */
    @NotEmpty
    private List<String> nodes = new ArrayList<>();

    /**
     * Имя базы данных PostgreSQL.
     * <p>
     * Источник — environment variable {@code PG_DATABASE}, инжектируемая
     * {@link space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor}-ом в виде
     * {@code platform.datasource.postgres-cluster.database}.
     * Потребитель может переопределить значение в собственном {@code application.yml}.
     * <p>
     * JSR-303: значение не должно быть пустым — без имени БД нельзя сформировать корректный JDBC/R2DBC URL.
     */
    @NotBlank
    private String database;

    /**
     * Дополнительные свойства соединения, передаваемые PostgreSQL-драйверу через
     * query-string JDBC URL (для JDBC-стартера) или через
     * {@code ConnectionFactoryOptions.parse(url)} (для R2DBC-стартера).
     * Добавляются к обоим пулам (write и read).
     *
     * <p><b>Применяемые ключи.</b> Принимаются любые connection-параметры pgJDBC /
     * r2dbc-postgresql: {@code applicationName}, {@code sslmode}, {@code sslrootcert},
     * {@code connectTimeout}, {@code socketTimeout}, {@code options} и пр.
     * Оба драйвера используют одинаковые имена параметров, поэтому поле является
     * <i>транспорт-агностичным</i>: DTO используется и в JDBC-, и в R2DBC-стартере.
     *
     * <p><b>Источник.</b> Environment variable {@code PG_CONNECTION_PROPERTIES}
     * (properties-style: {@code key=value;key=value}).
     * EPP исключает из карты ключи {@code user}/{@code password} (case-insensitive) —
     * credentials поставляются драйверу из {@link Credentials} через
     * {@code setUsername}/{@code setPassword}, дублирование вызывает warning драйвера.
     *
     * <p><b>Регистр ключей.</b> Ключи сохраняются в исходном регистре —
     * pgJDBC/r2dbc-postgresql ожидают camelCase ({@code applicationName},
     * а не {@code application-name}).
     */
    private Map<String, String> connectionProperties = new LinkedHashMap<>();

    /**
     * Учётные данные для подключения к PostgreSQL.
     * <p>
     * Поставляются через канонические {@code platform.datasource.postgres-cluster.credentials.*}
     * с дефолтами {@code PG_USERNAME}/{@code PG_PASSWORD}, инжектируемыми
     * {@link space.br1440.platform.datasource.configuration.environment.PostgresClusterEnvironmentPostProcessor}-ом.
     *
     * <p><b>Замечание по passwordless-режимам.</b> Поля {@code username} и {@code password}
     * допускают {@code null}/blank: это нужно для локального Postgres с trust-аутентификацией
     * и для integration-тестов с testcontainers без пароля. Если приложение требует
     * обязательных credentials, бросать ошибку должна собственная стартап-проверка
     * приложения, а не стартер.
     */
    @Valid
    private Credentials credentials = new Credentials();

    private ReadReplica readReplica = new ReadReplica();

    @Getter @Setter @Accessors(chain = true)
    public static class Credentials {

        private String username;

        @JsonIgnore
        private String password;
    }

    /**
     * Профиль чтения с read-реплик.
     * Описывает, какие физические соединения попадают в read-пул.
     */
    @Getter @Setter @Accessors(chain = true)
    public static class ReadReplica {

        /**
         * Стратегия заполнения read-пула физическими соединениями (topology-слой).
         * <p>
         * Дефолт — {@link Strategy#LOAD_BALANCED}: read-нагрузка равномерно
         * распределяется между всеми pg-нодами кластера. Для write-heavy сервисов,
         * где Primary должен быть максимально разгружен, потребитель переопределяет
         * стратегию через собственный {@code application.yml}:
         */
        private Strategy strategy = Strategy.LOAD_BALANCED;

        /**
         * Стратегия заполнения read-пула физическими соединениями.
         * <p>
         * Подбирается под характер нагрузки микросервиса:
         * <ul>
         *   <li>{@link #SECONDARY_ONLY} — для write-heavy сервисов: читающие транзакции идут
         *       только на Secondary-ноду, снимая нагрузку с Primary.</li>
         *   <li>{@link #LOAD_BALANCED} — для read-heavy сервисов (default): читающие транзакции
         *       равномерно распределяются между всеми pg-нодами кластера
         *       ({@code targetServerType=any} + {@code loadBalanceHosts=true}), <b>включая Primary</b>.</li>
         * </ul>
         * <p>
         * Переключение стратегии не меняет write-путь: write-пул всегда направляет
         * соединения только на Primary ({@code targetServerType=primary}).
         */
        public enum Strategy {

            /**
             * Читать только с Secondary pg-ноды.
             * <p>
             * Для read-пула используется {@code targetServerType=preferSecondary} — драйвер
             * предпочтёт Secondary, но сможет подключиться к Primary при недоступности реплики.
             * Подходит для write-heavy сервисов, где Primary должен быть максимально разгружен от read-нагрузки.
             */
            SECONDARY_ONLY,

            /**
             * Балансировать чтение между всеми pg-нодами кластера, <b>включая Primary</b>.
             * <p>
             * Принудительно устанавливает {@code targetServerType=any} и
             * {@code loadBalanceHosts=true} для read-пула. Подходит для read-heavy сервисов,
             * где важно задействовать максимум доступных нод для чтения.
             * <p>
             * Read-пул остаётся {@code readOnly=true} — DML/DDL по-прежнему идут только через write-пул.
             */
            LOAD_BALANCED
        }
    }
}
