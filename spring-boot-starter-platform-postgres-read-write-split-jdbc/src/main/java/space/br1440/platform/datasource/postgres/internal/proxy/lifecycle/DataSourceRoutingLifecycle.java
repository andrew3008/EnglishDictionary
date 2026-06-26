package space.br1440.platform.datasource.postgres.internal.proxy.lifecycle;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.web.context.WebServerGracefulShutdownLifecycle;
import org.springframework.context.SmartLifecycle;

@Slf4j
public class DataSourceRoutingLifecycle implements SmartLifecycle {

    private final HikariDataSource writeDataSource;
    private final HikariDataSource readDataSource;

    private volatile boolean running = false;

    public DataSourceRoutingLifecycle(HikariDataSource writeDataSource,
                                      HikariDataSource readDataSource) {
        this.writeDataSource = writeDataSource;
        this.readDataSource = readDataSource;
    }

    @Override
    public void start() {
        running = true;
    }

    @Override
    public void stop() {
        running = false;

        closeQuietly(readDataSource, "read");
        closeQuietly(writeDataSource, "write");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        // TODO[spring-boot-4.x-migration]:
        //   В Spring Boot 4.0 SMART_LIFECYCLE_PHASE помечен @Deprecated(forRemoval=true)
        //   в пользу WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE.
        //   При миграции на 4.x: проверить актуальную константу и Javadoc и переписать на
        //     return WebServerApplicationContext.GRACEFUL_SHUTDOWN_PHASE - 1024;
        return WebServerGracefulShutdownLifecycle.SMART_LIFECYCLE_PHASE - 1024;
    }

    private void closeQuietly(HikariDataSource dataSource, String intent) {
        if (dataSource == null) {
            return;
        }

        if (dataSource.isClosed()) {
            log.debug("{} pool is already closed, skipping", intent);
            return;
        }

        try {
            dataSource.close();
            log.debug("Closed {} pool ({})", intent, dataSource.getPoolName());
        } catch (RuntimeException ex) {
            log.warn("Error closing {} pool ({}): {}", intent, dataSource.getPoolName(), ex.getMessage(), ex);
        }
    }
}
