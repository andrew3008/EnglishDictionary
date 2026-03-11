package space.br1440.platform.healthchecks.postgres;

import com.zaxxer.hikari.HikariDataSource;
import com.zaxxer.hikari.HikariPoolMXBean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@RequiredArgsConstructor
public class PoolHealthEvaluator {

    private final PostgresHealthProperties properties;

    public PoolHealthResult check(HikariDataSource hikari) {
        HikariPoolMXBean mxBean = hikari.getHikariPoolMXBean();

        int active = mxBean.getActiveConnections();
        int idle = mxBean.getIdleConnections();
        int total = mxBean.getTotalConnections();
        int waiting = mxBean.getThreadsAwaitingConnection();
        int maxSize = hikari.getMaximumPoolSize();
        int minIdle = hikari.getMinimumIdle();
        int availablePercent = (total > 0) ? (idle * 100) / total : 0;

        Map<String, Object> metrics = new LinkedHashMap<>();
        metrics.put("active", active);
        metrics.put("idle", idle);
        metrics.put("total", total);
        metrics.put("waiting", waiting);
        metrics.put("maxPoolSize", maxSize);
        metrics.put("minIdle", minIdle);
        metrics.put("availablePercent", availablePercent);

        List<String> warnings = collectWarnings(availablePercent, waiting, total, idle, maxSize);

        PoolHealthResult.PoolStatus poolStatus = PoolHealthResult.PoolStatus.HEALTHY;
        if (!warnings.isEmpty()) {
            poolStatus = PoolHealthResult.PoolStatus.DEGRADED;
            metrics.put("warnings", warnings);
        }

        metrics.put("status", poolStatus.name());
        return new PoolHealthResult(metrics, poolStatus);
    }

    private List<String> collectWarnings(int availablePercent,
                                         int waiting,
                                         int total,
                                         int idle,
                                         int maxSize) {
        List<String> warnings = new ArrayList<>();
        PostgresHealthProperties.ConnectionPool cfg = properties.getConnectionPool();

        if (availablePercent < cfg.getMinAvailablePercent()) {
            warnings.add(String.format(
                    "Available connections %d%% is below threshold %d%%",
                    availablePercent, cfg.getMinAvailablePercent()));
        }

        if (waiting > cfg.getMaxWaitingThreads()) {
            warnings.add(String.format(
                    "Waiting threads %d exceeds maximum %d",
                    waiting, cfg.getMaxWaitingThreads()));
        }

        if (total >= maxSize && idle == 0) {
            warnings.add("Connection pool exhausted: no idle connections available");
        }

        return warnings;
    }

    public record PoolHealthResult(Map<String, Object> metrics,
                                   PoolStatus poolStatus) {
        public enum PoolStatus {HEALTHY, DEGRADED}

        public boolean isDegraded() {
            return poolStatus == PoolStatus.DEGRADED;
        }
    }
}
