package space.br1440.platform.datasource.postgres;

import com.zaxxer.hikari.HikariDataSource;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.SmartLifecycle;

/**
 * Управляет порядком закрытия HikariCP пулов при graceful shutdown.
 *
 * <p>Spring вызывает {@link #stop()} в порядке <b>убывания</b> {@link #getPhase()}.
 * Этот компонент использует фазу {@code Integer.MAX_VALUE - 1}, что гарантирует
 * закрытие пулов <b>после</b> {@code WebServerGracefulShutdownLifecycle}
 * (phase = {@code Integer.MAX_VALUE}), то есть после завершения всех in-flight запросов.
 *
 * <p>Порядок shutdown:
 * <ol>
 *   <li>{@code Integer.MAX_VALUE} — WebServer перестаёт принимать запросы,
 *       ждёт завершения in-flight</li>
 *   <li>{@code Integer.MAX_VALUE - 1} — пулы закрываются (этот компонент)</li>
 * </ol>
 *
 * <p>Порядок закрытия read-пул → write-пул: сначала гасим читающую ветку,
 * затем пишущую — симметрично последовательности открытия и естественному
 * приоритету «важно дописать то, что пишется, прежде чем выгружать кеши чтения».
 */
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
        log.info("Graceful shutdown: closing datasource pools");

        closeQuietly(readDataSource, "read");
        closeQuietly(writeDataSource, "write");
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public int getPhase() {
        return Integer.MAX_VALUE - 1;
    }

    private void closeQuietly(HikariDataSource ds, String intent) {
        try {
            if (ds != null && !ds.isClosed()) {
                ds.close();
                log.debug("Closed {} pool", intent);
            }
        } catch (Exception e) {
            log.warn("Error closing {} pool: {}", intent, e.getMessage());
        }
    }
}
