package space.br1440.platform.datasource.config;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.LazyConnectionDataSourceProxy;
import space.br1440.platform.datasource.postgres.PostgresDataSourceRoutingAutoConfiguration;
import space.br1440.platform.datasource.postgres.api.properties.PgClusterProperties;
import space.br1440.platform.datasource.postgres.internal.bootstrap.OnClientDataSourceRoutingCondition;
import space.br1440.platform.datasource.postgres.internal.proxy.core.PasswordExpiryRetryDataSourceProxy;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import space.br1440.platform.datasource.postgres.internal.proxy.core.RoutingDataSource;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@DisplayName("PostgresRoutingAutoConfiguration — поведение password retry")
class PostgresRoutingPasswordRetryBehaviorTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PostgresDataSourceRoutingAutoConfiguration.class,
                    DataSourceAutoConfiguration.class))
            .withPropertyValues(
                    OnClientDataSourceRoutingCondition.PROPERTY + "=CLIENT",
                    PgClusterProperties.PREFIX + ".nodes[0].host=localhost",
                    PgClusterProperties.PREFIX + ".nodes[0].port=5432",
                    PgClusterProperties.PREFIX + ".database=testdb",
                    PgClusterProperties.PREFIX + ".credentials.username=sa",
                    PgClusterProperties.PREFIX + ".credentials.password=sa",
                    PgClusterProperties.PREFIX + ".pool.write.initialization-fail-timeout=-1",
                    PgClusterProperties.PREFIX + ".pool.read.initialization-fail-timeout=-1");

    @BeforeEach
    void resetStubState() {
        StubPhysicalPoolsState.reset();
    }

    @Test
    @DisplayName("retry enabled: @Primary dataSource выполняет soft-evict и retry на 28P01")
    void retryEnabled_primaryDataSource_softEvictsAndRetriesOn28P01() throws SQLException {
        contextRunner
                .withUserConfiguration(StubPhysicalPoolsConfiguration.class)
                .run(context -> {
                    resetCountersAfterContextStartup();

                    DataSource primary = context.getBean("dataSource", DataSource.class);

                    Connection connection = primary.getConnection();

                    assertThat(connection).isNotNull();
                    assertThat(StubPhysicalPoolsState.WRITE_CONNECT_ATTEMPTS).hasValue(2);
                    verify(StubPhysicalPoolsState.WRITE_MX_BEAN).softEvictConnections();
                    assertThat(context.getBean("dataSource"))
                            .isInstanceOf(LazyConnectionDataSourceProxy.class);
                });
    }

    @Test
    @DisplayName("retry enabled: lowercase 28p01 на routing-target тоже триггерит retry")
    void retryEnabled_lowercase28p01_triggersRetry() throws SQLException {
        StubPhysicalPoolsState.invalidPasswordSqlState = "28p01";

        contextRunner
                .withUserConfiguration(StubPhysicalPoolsConfiguration.class)
                .run(context -> {
                    resetCountersAfterContextStartup();

                    DataSource primary = context.getBean("dataSource", DataSource.class);

                    Connection connection = primary.getConnection();

                    assertThat(connection).isNotNull();
                    assertThat(StubPhysicalPoolsState.WRITE_CONNECT_ATTEMPTS).hasValue(2);
                    verify(StubPhysicalPoolsState.WRITE_MX_BEAN).softEvictConnections();
                });
    }

    @Test
    @DisplayName("retry disabled: 28P01 пробрасывается без повторной попытки")
    void retryDisabled_noSecondAttemptOn28P01() throws SQLException {
        StubPhysicalPoolsState.alwaysThrowInvalidPassword = true;

        contextRunner
                .withUserConfiguration(StubPhysicalPoolsConfiguration.class)
                .withPropertyValues(PgClusterProperties.PREFIX + ".retry.invalid-password-retry-enabled=false")
                .run(context -> {
                    resetCountersAfterContextStartup();

                    RoutingDataSource routing = context.getBean(RoutingDataSource.class);
                    Map<Object, DataSource> targets = routing.getResolvedDataSources();

                    assertThat(targets.values())
                            .allMatch(ds -> ds instanceof HikariDataSource);

                    DataSource writeTarget = targets.get(DataSourceType.WRITE);

                    assertThatThrownBy(writeTarget::getConnection)
                            .satisfies(ex -> assertThat(extractSqlState(ex)).isEqualToIgnoringCase("28P01"));
                    assertThat(StubPhysicalPoolsState.WRITE_CONNECT_ATTEMPTS).hasValue(1);
                });
    }

    private static void resetCountersAfterContextStartup() {
        StubPhysicalPoolsState.WRITE_CONNECT_ATTEMPTS.set(0);
        clearInvocations(StubPhysicalPoolsState.WRITE_MX_BEAN);
    }

    private static String extractSqlState(Throwable throwable) {
        Throwable current = throwable;
        while (current != null) {
            if (current instanceof SQLException sql && sql.getSQLState() != null && !sql.getSQLState().isEmpty()) {
                return sql.getSQLState();
            }
            current = current.getCause();
        }
        return "";
    }

    static final class StubPhysicalPoolsState {

        static final AtomicInteger WRITE_CONNECT_ATTEMPTS = new AtomicInteger();
        static final HikariPoolMXBean WRITE_MX_BEAN = mock(HikariPoolMXBean.class);
        static final Connection WRITE_CONNECTION = mock(Connection.class);
        static final Connection READ_CONNECTION = mock(Connection.class);
        static String invalidPasswordSqlState = "28P01";
        static boolean alwaysThrowInvalidPassword = false;

        static {
            configureWriteConnectionMock();
        }

        private StubPhysicalPoolsState() {
        }

        static void reset() {
            WRITE_CONNECT_ATTEMPTS.set(0);
            invalidPasswordSqlState = "28P01";
            alwaysThrowInvalidPassword = false;
            configureWriteConnectionMock();
        }

        private static void configureWriteConnectionMock() {
            try {
                Statement statement = mock(Statement.class);
                when(WRITE_CONNECTION.getAutoCommit()).thenReturn(true);
                when(WRITE_CONNECTION.isValid(anyInt())).thenReturn(true);
                when(WRITE_CONNECTION.createStatement()).thenReturn(statement);
            } catch (SQLException ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    @Configuration(proxyBeanMethods = false)
    static class StubPhysicalPoolsConfiguration {

        @Bean(name = "writeDataSource")
        HikariDataSource writeDataSource() throws SQLException {
            HikariDataSource dataSource = mock(HikariDataSource.class);
            when(dataSource.getHikariPoolMXBean()).thenReturn(StubPhysicalPoolsState.WRITE_MX_BEAN);
            when(dataSource.getConnection()).thenAnswer(invocation -> {
                int attempt = StubPhysicalPoolsState.WRITE_CONNECT_ATTEMPTS.getAndIncrement();
                if (StubPhysicalPoolsState.alwaysThrowInvalidPassword || attempt == 0) {
                    throw new SQLException("password authentication failed", StubPhysicalPoolsState.invalidPasswordSqlState);
                }
                return StubPhysicalPoolsState.WRITE_CONNECTION;
            });
            return dataSource;
        }

        @Bean(name = "readDataSource")
        HikariDataSource readDataSource() throws SQLException {
            HikariDataSource dataSource = mock(HikariDataSource.class);
            when(dataSource.getConnection()).thenReturn(StubPhysicalPoolsState.READ_CONNECTION);
            return dataSource;
        }
    }
}
