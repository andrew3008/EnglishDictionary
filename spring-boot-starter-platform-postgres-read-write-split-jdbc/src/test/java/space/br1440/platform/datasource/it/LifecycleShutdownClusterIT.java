package space.br1440.platform.datasource.it;

import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.SmartLifecycle;
import space.br1440.platform.datasource.postgres.internal.proxy.lifecycle.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.support.junit.ClusterRoutingItProperties;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что graceful shutdown библиотеки реально закрывает HikariCP-пулы:
 * после {@link ConfigurableApplicationContext#close()} оба пула становятся {@code closed=true}.
 * <p>
 * Контекст стартует с canonical {@value PgClusterProperties#PREFIX}.* keys
 * (см. {@link ClusterRoutingItProperties#baseClusterContextProperties}).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("DataSourceRoutingLifecycle: graceful shutdown")
class LifecycleShutdownClusterIT {

    private ConfigurableApplicationContext context;

    @BeforeAll
    void start(PgCluster cluster) {
        Map<String, Object> props = new HashMap<>(ClusterRoutingItProperties.baseClusterContextProperties(cluster));

        SpringApplication app = new SpringApplication(LifecycleApp.class);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setDefaultProperties(props);
        this.context = app.run();
    }

    @AfterAll
    void teardownIfStillRunning() {
        if (context != null && context.isActive()) {
            context.close();
        }
    }

    @Test
    @DisplayName("Graceful shutdown закрывает оба HikariCP-пула + stop() идемпотентен")
    void contextClose_closesBothPools_andStopIsIdempotent() {
        HikariDataSource master = (HikariDataSource) context.getBean("writeDataSource");
        HikariDataSource replica = (HikariDataSource) context.getBean("readDataSource");
        DataSourceRoutingLifecycle lifecycle = context.getBean(DataSourceRoutingLifecycle.class);

        assertThat(master.isClosed()).isFalse();
        assertThat(replica.isClosed()).isFalse();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(SmartLifecycle.DEFAULT_PHASE - 2048);

        context.close();

        assertThat(master.isClosed()).as("master pool must be closed after shutdown").isTrue();
        assertThat(replica.isClosed()).as("replica pool must be closed after shutdown").isTrue();
        assertThat(lifecycle.isRunning()).isFalse();

        // Идемпотентность: повторный вызов stop() на уже закрытых пулах должен пройти
        // без исключений (early-return по isClosed() в closeQuietly).
        org.assertj.core.api.Assertions.assertThatCode(lifecycle::stop).doesNotThrowAnyException();
        assertThat(lifecycle.isRunning()).isFalse();
        assertThat(master.isClosed()).isTrue();
        assertThat(replica.isClosed()).isTrue();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class LifecycleApp {
    }
}
