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
import space.br1440.platform.datasource.configuration.condition.OnPgTopologyDirectCondition;
import space.br1440.platform.datasource.postgres.DataSourceRoutingLifecycle;
import space.br1440.platform.datasource.support.junit.PgClusterExtension;
import space.br1440.platform.datasource.support.junit.PgClusterExtension.PgCluster;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Проверяет, что graceful shutdown библиотеки реально закрывает HikariCP-пулы:
 * после {@link ConfigurableApplicationContext#close()} оба пула становятся {@code closed=true}.
 * <p>
 * Контекст стартует по zero-config контракту: {@code PG_*} «environment variables»
 * подаются через {@link SpringApplication#setDefaultProperties(Map)}, и
 * {@code PostgresClusterEnvironmentPostProcessor} штатно мапит их в канонические
 * {@code platform.datasource.postgres-cluster.*} ключи.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@ExtendWith(PgClusterExtension.class)
@DisplayName("DataSourceRoutingLifecycle: graceful shutdown")
class LifecycleShutdownClusterIT {

    private PgCluster cluster;
    private ConfigurableApplicationContext context;

    @BeforeAll
    void start(PgCluster cluster) {
        this.cluster = cluster;
        Map<String, Object> props = new HashMap<>();
        props.put("spring.main.web-application-type", "none");
        props.put("spring.main.banner-mode", "off");
        props.put(OnPgTopologyDirectCondition.ENV_VAR, OnPgTopologyDirectCondition.DIRECT);
        props.put("PG_CLUSTER_NODES", cluster.pgNodesString());
        props.put("PG_DATABASE", cluster.dbName());
        props.put("PG_USERNAME", cluster.username());
        props.put("PG_PASSWORD", cluster.password());

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
    @DisplayName("Graceful shutdown закрывает оба HikariCP-пула")
    void contextClose_closesBothPools() {
        HikariDataSource master = (HikariDataSource) context.getBean("writeDataSource");
        HikariDataSource replica = (HikariDataSource) context.getBean("readDataSource");
        DataSourceRoutingLifecycle lifecycle = context.getBean(DataSourceRoutingLifecycle.class);

        assertThat(master.isClosed()).isFalse();
        assertThat(replica.isClosed()).isFalse();
        assertThat(lifecycle.isRunning()).isTrue();
        assertThat(lifecycle.getPhase()).isEqualTo(Integer.MAX_VALUE - 1);

        context.close();

        assertThat(master.isClosed()).as("master pool must be closed after shutdown").isTrue();
        assertThat(replica.isClosed()).as("replica pool must be closed after shutdown").isTrue();
        assertThat(lifecycle.isRunning()).isFalse();
    }

    @SpringBootApplication(exclude = DataSourceAutoConfiguration.class)
    public static class LifecycleApp {
    }
}
