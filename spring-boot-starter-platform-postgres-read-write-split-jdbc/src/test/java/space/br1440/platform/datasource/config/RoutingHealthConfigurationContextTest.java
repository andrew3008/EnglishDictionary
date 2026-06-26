package space.br1440.platform.datasource.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import space.br1440.platform.datasource.postgres.api.properties.DataSourceRoutingMode;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.api.properties.PgNode;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.api.properties.ReadReplicaProperties;
import space.br1440.platform.datasource.postgres.internal.integration.health.DataSourceRoutingHealthIndicator;
import space.br1440.platform.datasource.postgres.internal.integration.health.DegradedStatusConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.health.RoutingHealthConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.health.RoutingHealthProperties;

import javax.sql.DataSource;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class RoutingHealthConfigurationContextTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(RoutingHealthConfiguration.class))
            .withPropertyValues(
                    "management.health.routing.enabled=true",
                    RoutingHealthProperties.PREFIX + ".timeout=5s",
                    RoutingHealthProperties.PREFIX + ".query-timeout=3s"
            )
            .withUserConfiguration(TestHarness.class);

    @Test
    void createsRoutingHealthIndicatorWithDataSourceParameters() {
        runner.run(context -> assertThat(context).hasSingleBean(DataSourceRoutingHealthIndicator.class));
    }

    @Test
    void consumerRoutingHealthIndicatorNameCausesBackoff() {
        runner.withUserConfiguration(CustomRoutingHealthIndicator.class)
                .run(context -> {
                    assertThat(context).doesNotHaveBean(DataSourceRoutingHealthIndicator.class);
                    assertThat(context).hasBean("routingHealthIndicator");
                });
    }

    @Test
    void disabledRoutingHealthIndicatorIsNotRegistered() {
        runner.withPropertyValues("management.health.routing.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean(DataSourceRoutingHealthIndicator.class));
    }

    @Test
    void degradedStatusConfigurationIsNotAutoImported() {
        runner.run(context -> {
            assertThat(context).doesNotHaveBean("routingStatusAggregator");
            assertThat(context).doesNotHaveBean("routingHttpCodeStatusMapper");
        });
    }

    @Test
    void routingHealthPropertiesDefaultsAreBound() {
        runner.run(context -> {
            RoutingHealthProperties properties = context.getBean(RoutingHealthProperties.class);
            assertThat(properties.timeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.queryTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.queryTimeoutSeconds()).isEqualTo(3);
        });
    }

    @Test
    void invalidRoutingHealthPropertiesFailBinding() {
        runner.withPropertyValues(RoutingHealthProperties.PREFIX + ".timeout=0s")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test
    void proxyDataSourceParametersAreAccepted() {
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RoutingHealthConfiguration.class))
                .withPropertyValues("management.health.routing.enabled=true")
                .withUserConfiguration(ProxyDataSourceHarness.class)
                .run(context -> assertThat(context).hasSingleBean(DataSourceRoutingHealthIndicator.class));
    }

    @Test
    void configurationMetadataContainsHealthProperties() {
        runner.run(context -> assertThat(context).hasSingleBean(RoutingHealthProperties.class));
    }

    @Test
    void explicitDegradedStatusConfigurationStillWorksAsOptIn() {
        runner.withUserConfiguration(OptInDegradedStatus.class)
                .run(context -> {
                    assertThat(context).hasSingleBean(StatusAggregator.class);
                    assertThat(context).hasSingleBean(HttpCodeStatusMapper.class);
                });
    }

    @Configuration
    static class ProxyDataSourceHarness {

        @Bean
        PgClusterProperties pgClusterProperties() {
            return new PgClusterProperties(
                    DataSourceRoutingMode.CLIENT,
                    java.util.List.of(new PgNode("localhost", 5432)),
                    "testdb",
                    new PgClusterProperties.Credentials("u", "p"),
                    null,
                    new ReadReplicaProperties(ReadMode.SECONDARY_PREFERRED),
                    null,
                    null
            );
        }

        @Bean("writeDataSource")
        DataSource writeDataSource() {
            return new org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy(mock(DataSource.class));
        }

        @Bean("readDataSource")
        DataSource readDataSource() {
            return new org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy(mock(DataSource.class));
        }
    }

    @Configuration
    static class TestHarness {

        @Bean
        PgClusterProperties pgClusterProperties() {
            return new PgClusterProperties(
                    DataSourceRoutingMode.CLIENT,
                    java.util.List.of(new PgNode("localhost", 5432)),
                    "testdb",
                    new PgClusterProperties.Credentials("u", "p"),
                    null,
                    new ReadReplicaProperties(ReadMode.SECONDARY_PREFERRED),
                    null,
                    null
            );
        }

        @Bean("writeDataSource")
        DataSource writeDataSource() {
            return mock(DataSource.class);
        }

        @Bean("readDataSource")
        DataSource readDataSource() {
            return mock(DataSource.class);
        }
    }

    @Configuration
    static class CustomRoutingHealthIndicator {

        @Bean("routingHealthIndicator")
        HealthIndicator routingHealthIndicator() {
            return () -> org.springframework.boot.actuate.health.Health.up().build();
        }
    }

    @Configuration
    @Import(DegradedStatusConfiguration.class)
    static class OptInDegradedStatus {
    }
}
