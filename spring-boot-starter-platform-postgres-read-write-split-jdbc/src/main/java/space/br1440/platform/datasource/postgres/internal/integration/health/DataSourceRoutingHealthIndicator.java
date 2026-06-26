package space.br1440.platform.datasource.postgres.internal.integration.health;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.datasource.postgres.api.properties.ReadMode;

import javax.sql.DataSource;
import java.sql.*;
import java.util.LinkedHashMap;
import java.util.Map;

public class DataSourceRoutingHealthIndicator implements HealthIndicator, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(DataSourceRoutingHealthIndicator.class);

    private static final String DETAIL_READ_MODE = "readMode";
    private static final String DETAIL_STATUS = "status";
    private static final String DETAIL_PG_ROLE = "pgRole";
    private static final String DETAIL_FAILURE_TYPE = "failureType";

    private static final String PG_ROLE_PRIMARY = "primary";
    private static final String PG_ROLE_STANDBY = "standby";

    private static final Status DEGRADED = DegradedHealthStatus.INSTANCE;

    /*language=TEXT*/
    private static final String QUERY_PG_ROLE = "SELECT pg_is_in_recovery()";

    private final DataSource writeDataSource;
    private final DataSource readDataSource;
    private final ReadMode readMode;
    private final RoutingHealthProperties healthProperties;
    private final RoutingHealthProbeCoordinator coordinator;

    public DataSourceRoutingHealthIndicator(DataSource writeDataSource,
                                            DataSource readDataSource,
                                            ReadMode readMode,
                                            RoutingHealthProperties healthProperties) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
        this.readMode = readMode;
        this.healthProperties = healthProperties;
        this.coordinator = new RoutingHealthProbeCoordinator();
    }

    @Override
    public Health health() {
        try {
            ProbeResults results = coordinator.probe(
                    healthProperties.timeout(),
                    readMode,
                    () -> check(writeDataSource),
                    () -> check(readDataSource)
            );
            return buildHealth(results);
        } catch (ProbeInterruptedException ex) {
            return Health.down().withDetail("reason", "interrupted").build();
        }
    }

    @Override
    public void destroy() {
        coordinator.destroy();
    }

    HealthCheckResult check(DataSource dataSource) {
        Connection connection;
        try {
            connection = dataSource.getConnection();
        } catch (SQLException ex) {
            log.debug("Routing JDBC connection acquisition failed", ex);
            return HealthCheckResult.failed(FailureType.CONNECTION_FAILURE);
        }

        try (connection;
             Statement statement = connection.createStatement()) {
            statement.setQueryTimeout(healthProperties.queryTimeoutSeconds());
            try (ResultSet resultSet = statement.executeQuery(QUERY_PG_ROLE)) {
                if (!resultSet.next()) {
                    return HealthCheckResult.failed(FailureType.INVALID_RESULT);
                }

                boolean inRecovery = resultSet.getBoolean(1);
                if (resultSet.wasNull()) {
                    return HealthCheckResult.failed(FailureType.INVALID_RESULT);
                }

                String pgRole = inRecovery ? PG_ROLE_STANDBY : PG_ROLE_PRIMARY;
                return HealthCheckResult.success(pgRole);
            }
        } catch (SQLTimeoutException ex) {
            log.debug("Routing JDBC query timed out", ex);
            return HealthCheckResult.failed(FailureType.TIMEOUT);
        } catch (SQLException ex) {
            log.debug("Routing JDBC query failed", ex);
            return HealthCheckResult.failed(FailureType.SQL_EXCEPTION);
        } catch (Exception ex) {
            log.debug("Routing JDBC health check failed", ex);
            return HealthCheckResult.failed(FailureType.INTERNAL_ERROR);
        }
    }

    private Health buildHealth(ProbeResults results) {
        Status status = aggregateStatus(results.write().up(), results.read().up(), results.readMode());
        return Health.status(status)
                .withDetail(DETAIL_READ_MODE, results.readMode().name())
                .withDetail("write", componentDetails(results.write()))
                .withDetail("read", componentDetails(results.read()))
                .build();
    }

    static Status aggregateStatus(boolean writeUp, boolean readUp, ReadMode readMode) {
        boolean readCritical = readMode == ReadMode.STRICT_SECONDARY;

        if (!readCritical) {
            if (writeUp) {
                return Status.UP;
            }

            return readUp ? DEGRADED : Status.DOWN;
        }

        if (writeUp && readUp) {
            return Status.UP;
        }

        if (writeUp || readUp) {
            return DEGRADED;
        }

        return Status.DOWN;
    }

    private static Map<String, Object> componentDetails(HealthCheckResult result) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (result.up()) {
            map.put(DETAIL_STATUS, Status.UP.getCode());
            map.put(DETAIL_PG_ROLE, result.pgRole());
        } else {
            map.put(DETAIL_STATUS, Status.DOWN.getCode());
            map.put(DETAIL_FAILURE_TYPE, result.failureType().name());
        }

        return map;
    }
}
