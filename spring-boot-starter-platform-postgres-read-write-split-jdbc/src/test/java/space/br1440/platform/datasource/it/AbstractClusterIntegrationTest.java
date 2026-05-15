package space.br1440.platform.datasource.it;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;

import java.util.HashMap;
import java.util.Map;

/**
 * Базовый класс для интеграционных тестов, требующих живой PostgreSQL primary+replica кластер.
 * <p>
 * Поднимает один кластер на класс ({@link PgClusterExtension}), стартует Spring Boot
 * контекст с авто-конфигурацией библиотеки, и предоставляет наследникам прозрачный
 * доступ к {@link ConfigurableApplicationContext}.
 *
 * <h3>Жизненный цикл</h3>
 * <ul>
 *   <li>{@code @BeforeAll}: PgCluster стартует (primary + replica + replication slot)</li>
 *   <li>{@code @BeforeAll} (наследник): Spring контекст стартует с pg-nodes из кластера</li>
 *   <li>{@code @Test}: тест получает контекст, бины, source/replica datasources</li>
 *   <li>{@code @AfterAll}: контекст и кластер закрываются</li>
 * </ul>
 *
 * <h3>EnvironmentPostProcessor и default-properties</h3>
 * <p>В этом IT мы запускаем полный {@link SpringApplication}, поэтому
 * {@code PostgresClusterEnvironmentPostProcessor} <i>срабатывает</i>: он видит
 * {@code PG_TOPOLOGY=DIRECT} и {@code PG_CLUSTER_NODES=...} в default-properties и
 * инжектирует канонические {@code platform.datasource.postgres-cluster.*} ключи.
 * Это адекватно отражает реальный production-сценарий с {@code PG_*} env-переменными
 * Kubernetes ConfigMap/Secret.
 *
 * <h3>Override-точки</h3>
 * Наследники могут добавить свои properties через {@link #extraProperties()},
 * либо подменить базовый класс приложения через {@link #applicationClass()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
public abstract class AbstractClusterIntegrationTest {

    protected PgClusterExtension.PgCluster cluster;
    protected ConfigurableApplicationContext context;

    @BeforeAll
    void startSpringContext(PgClusterExtension.PgCluster cluster) {
        this.cluster = cluster;
        Map<String, Object> properties = new HashMap<>();
        properties.put("spring.main.web-application-type", "none");
        properties.put("spring.main.banner-mode", "off");
        properties.put("logging.level.space.br1440.platform.datasource", "DEBUG");
        // Активация стартера: имитация env PG_TOPOLOGY=DIRECT через property-binding.
        properties.put(OnPgTopologyDirectCondition.ENV_VAR, OnPgTopologyDirectCondition.DIRECT);
        // PG_* env-переменные Kubernetes ConfigMap/Secret. PostgresClusterEnvironmentPostProcessor
        // прочитает их и инжектирует platform.datasource.postgres-cluster.* в Environment.
        properties.put("PG_CLUSTER_NODES", cluster.pgNodesString());
        properties.put("PG_DATABASE", cluster.dbName());
        properties.put("PG_USERNAME", cluster.username());
        properties.put("PG_PASSWORD", cluster.password());
        properties.put("platform.datasource.postgres-cluster.hikari.write.maximum-pool-size", "5");
        properties.put("platform.datasource.postgres-cluster.hikari.read.maximum-pool-size", "5");
        properties.put("platform.datasource.postgres-cluster.hikari.write.connection-timeout", "5000");
        properties.put("platform.datasource.postgres-cluster.hikari.read.connection-timeout", "5000");
        properties.putAll(extraProperties());

        SpringApplication app = new SpringApplication(applicationClass());
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(properties);
        this.context = app.run();
    }

    @AfterAll
    void stopSpringContext() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    /** Override для добавления специфичных property значений. */
    protected Map<String, Object> extraProperties() {
        return Map.of();
    }

    /** Override, если тесту нужно заменить spring application class. */
    protected Class<?> applicationClass() {
        return DefaultClusterApp.class;
    }

    @SpringBootApplication(exclude = {DataSourceAutoConfiguration.class})
    public static class DefaultClusterApp {
    }
}
