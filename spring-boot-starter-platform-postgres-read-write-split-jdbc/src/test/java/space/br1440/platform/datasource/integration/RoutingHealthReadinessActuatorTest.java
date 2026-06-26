package space.br1440.platform.datasource.integration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HttpCodeStatusMapper;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.actuate.health.StatusAggregator;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;
import space.br1440.platform.datasource.postgres.internal.integration.health.DataSourceRoutingHealthIndicator;
import space.br1440.platform.datasource.postgres.internal.integration.health.DegradedStatusConfiguration;
import space.br1440.platform.datasource.postgres.internal.integration.health.RoutingHealthProperties;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RoutingHealthReadinessActuatorTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(DegradedRoutingHealthConfig.class);

    @Test
    void readinessGroupMapsDegradedTo503() {
        runner.run(context -> {
            StatusAggregator aggregator = context.getBean(StatusAggregator.class);
            HttpCodeStatusMapper mapper = context.getBean(HttpCodeStatusMapper.class);
            DataSourceRoutingHealthIndicator indicator = context.getBean(DataSourceRoutingHealthIndicator.class);

            Health routing = indicator.health();
            assertThat(routing.getStatus().getCode()).isEqualTo("DEGRADED");

            Status aggregate = aggregator.getAggregateStatus(Set.of(Status.UP, routing.getStatus()));
            assertThat(aggregate.getCode()).isEqualTo("DEGRADED");
            assertThat(mapper.getStatusCode(aggregate)).isEqualTo(503);
        });
    }

    @Configuration
    @Import(DegradedStatusConfiguration.class)
    static class DegradedRoutingHealthConfig {

        @Bean("writeDataSource")
        DataSource writeDataSource() throws Exception {
            DataSource ds = mock(DataSource.class);
            Connection conn = mock(Connection.class);
            Statement stmt = mock(Statement.class);
            ResultSet rs = mock(ResultSet.class);
            when(ds.getConnection()).thenReturn(conn);
            when(conn.createStatement()).thenReturn(stmt);
            when(stmt.executeQuery(anyString())).thenReturn(rs);
            when(rs.next()).thenReturn(true);
            when(rs.getBoolean(1)).thenReturn(false);
            when(rs.wasNull()).thenReturn(false);
            return ds;
        }

        @Bean("readDataSource")
        DataSource readDataSource() throws Exception {
            DataSource failing = mock(DataSource.class);
            when(failing.getConnection()).thenThrow(new java.sql.SQLException("down"));
            return failing;
        }

        @Bean("routingHealthIndicator")
        DataSourceRoutingHealthIndicator routingHealthIndicator(
                DataSource writeDataSource,
                DataSource readDataSource) {
            RoutingHealthProperties properties = new RoutingHealthProperties(
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(3));
            return new DataSourceRoutingHealthIndicator(
                    writeDataSource,
                    readDataSource,
                    ReadMode.STRICT_SECONDARY,
                    properties);
        }
    }
}
