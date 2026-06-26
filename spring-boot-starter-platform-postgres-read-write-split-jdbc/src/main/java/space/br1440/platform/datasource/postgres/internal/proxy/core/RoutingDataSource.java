package space.br1440.platform.datasource.postgres.internal.proxy.core;

import space.br1440.platform.datasource.postgres.api.routing.DataSourceContextHolder;
import space.br1440.platform.datasource.postgres.api.routing.DataSourceType;
import io.micrometer.observation.Observation;
import org.jetbrains.annotations.NotNull;
import org.slf4j.MDC;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import space.br1440.platform.datasource.postgres.api.exception.ReadDataSourceUnavailableException;
import space.br1440.platform.datasource.postgres.api.exception.WriteDataSourceUnavailableException;
import space.br1440.platform.datasource.postgres.internal.integration.metrics.RoutingMetricsCollector;
import space.br1440.platform.datasource.postgres.internal.integration.observation.PoolNames;
import space.br1440.platform.datasource.postgres.internal.integration.observation.RoutingObservationInterceptor;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.SQLTransientConnectionException;
import java.util.Map;
import java.util.Objects;

/**
 * Маршрутизирует подключения в write-пул (WRITE) или read-пул (READ).
 * <p>
 * Решение принимается на основе:
 * <ol>
 *   <li>Явного override через {@link DataSourceContextHolder} ({@code @UseReadReplica})</li>
 *   <li>Флага {@code readOnly} из {@link TransactionSynchronizationManager}</li>
 *   <li>По умолчанию — WRITE (write-пул)</li>
 * </ol>
 * <p>
 */
public class RoutingDataSource extends AbstractRoutingDataSource {

    private static final String MDC_DB_TARGET = "db.target";
    private static final String MDC_DB_POOL = "db.pool";

    private final DataSource writeDataSource;
    private final DataSource readDataSource;
    private final RoutingMetricsCollector metricsCollector;
    private final RoutingObservationInterceptor observationInterceptor;

    public RoutingDataSource(DataSource writeDataSource,
                             DataSource readDataSource,
                             RoutingMetricsCollector metricsCollector) {
        this(writeDataSource, readDataSource, metricsCollector, null);
    }

    public RoutingDataSource(DataSource writeDataSource,
                             DataSource readDataSource,
                             RoutingMetricsCollector metricsCollector,
                             RoutingObservationInterceptor observationInterceptor) {
        this.writeDataSource = Objects.requireNonNull(
                writeDataSource,
                "writeDataSource must not be null: required dependency of RoutingDataSource"
        );

        this.readDataSource = Objects.requireNonNull(
                readDataSource,
                "readDataSource must not be null: required dependency of RoutingDataSource"
        );

        this.metricsCollector = metricsCollector;
        this.observationInterceptor = observationInterceptor;
    }

    @Override
    public void afterPropertiesSet() {
        setTargetDataSources(Map.of(
                DataSourceType.WRITE, writeDataSource,
                DataSourceType.READ, readDataSource
        ));

        setDefaultTargetDataSource(writeDataSource);

        super.afterPropertiesSet();
    }

    @Override
    public @NotNull Connection getConnection() throws SQLException {
        DataSourceType key = resolveKey();
        DataSource target = resolveTarget(key);
        setMdc(key, target);

        Observation observation = startObservation(key, target);
        try {
            return target.getConnection();
        } catch (SQLTransientConnectionException e) {
            if (observation != null) {
                observation.error(e);
            }

            wrapAndThrow(key, e);

            throw e;
        } finally {
            if (observation != null) {
                observation.stop();
            }

            clearMdc();
        }
    }

    @Override
    public @NotNull Connection getConnection(@NotNull String username, @NotNull String password) throws SQLException {
        DataSourceType key = resolveKey();
        DataSource target = resolveTarget(key);
        setMdc(key, target);

        Observation observation = startObservation(key, target);
        try {
            return target.getConnection(username, password);
        } catch (SQLTransientConnectionException e) {
            if (observation != null) {
                observation.error(e);
            }

            wrapAndThrow(key, e);

            throw e;
        } finally {
            if (observation != null) {
                observation.stop();
            }

            clearMdc();
        }
    }

    @Override
    protected @NotNull DataSource determineTargetDataSource() {
        return resolveTarget(resolveKey());
    }

    @Override
    protected Object determineCurrentLookupKey() {
        return resolveKey();
    }

    DataSourceType resolveKey() {
        DataSourceType explicit = DataSourceContextHolder.currentIfSet();
        if (explicit != null) {
            return explicit;
        }

        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
            return DataSourceType.READ;
        }

        return DataSourceType.WRITE;
    }

    private DataSource resolveTarget(DataSourceType intent) {
        if (metricsCollector != null) {
            metricsCollector.recordRouting(intent);
        }

        return (intent == DataSourceType.READ) ? readDataSource : writeDataSource;
    }

    private void setMdc(DataSourceType intent, DataSource target) {
        MDC.put(MDC_DB_TARGET, intent.name());

        String poolName = PoolNames.nameOf(target);
        if (poolName != null) {
            MDC.put(MDC_DB_POOL, poolName);
        }
    }

    private void clearMdc() {
        MDC.remove(MDC_DB_TARGET);
        MDC.remove(MDC_DB_POOL);
    }

    private void wrapAndThrow(DataSourceType intent, SQLTransientConnectionException cause) {
        if (intent == DataSourceType.WRITE) {
            throw new WriteDataSourceUnavailableException("Write DataSource is unavailable: " + cause.getMessage(), cause);
        }

        throw new ReadDataSourceUnavailableException("Read DataSource is unavailable: " + cause.getMessage(), cause);
    }

    private Observation startObservation(DataSourceType key, DataSource target) {
        if (observationInterceptor == null) {
            return null;
        }

        return observationInterceptor.createObservation(key, target).start();
    }
}
