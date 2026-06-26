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
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.util.HashMap;
import java.util.Map;

/**
 * Базовый класс для интеграционных тестов, требующих живой PostgreSQL primary+replica кластер.
 * <p>
 * Поднимает один кластер на класс ({@link PgClusterExtension}), стартует Spring Boot
 * контекст с авто-конфигурацией библиотеки, и предоставляет наследникам прозрачный
 * доступ к {@link ConfigurableApplicationContext}.
 *
 * <h3>Property contract</h3>
 * <p>Контекст активируется через canonical keys {@value PgClusterProperties#PREFIX}.*
 * (indexed {@code nodes[i].host/port}) в {@code defaultProperties} — тот же контракт, что
 * {@code @DynamicPropertySource} в unit/slice IT. {@code PG_*} env vars через
 * {@code setDefaultProperties} EPP не видит (см. {@link ClusterRoutingItProperties}).
 *
 * <h3>Override-точки</h3>
 * Наследники могут добавить свои properties через {@link #extraProperties()},
 * либо подменить базовый класс приложения через {@link #applicationClass()}.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
public abstract class AbstractClusterIntegrationTest {

    protected PgCluster cluster;
    protected ConfigurableApplicationContext context;

    @BeforeAll
    void startSpringContext(PgCluster cluster) {
        this.cluster = cluster;
        Map<String, Object> properties = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));
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
