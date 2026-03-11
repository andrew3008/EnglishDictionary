package space.br1440.platform.healthchecks.async;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Базовый класс для асинхронных health-индикаторов.
 *
 * <p>В async-режиме ({@code asyncEnabled=true}) планировщик периодически вызывает
 * {@link #run()}, результат кэшируется в {@link HealthSnapshot}. При вызове
 * {@link #health()} из потока Kubernetes probe возвращается кэшированный результат.
 *
 * <h3>Синхронный режим</h3>
 * <p>При {@code asyncEnabled=false} {@link #health()} вызывает {@link #doHealthCheck()}
 * напрямую в потоке Actuator/probe <b>без timeout</b>. Этот режим предназначен для
 * unit-тестов и простых локальных проверок. В production с внешними зависимостями
 * (HTTP, DB, messaging) <b>используйте async-режим</b> — иначе долгий ответ зависимости
 * приведёт к истечению {@code timeoutSeconds} Kubernetes probe и каскадным рестартам.
 */
@Slf4j
public abstract class AsyncHealthIndicator implements HealthIndicator, Schedulable {

    private static final String LAST_CHECKED_KEY = "lastChecked";
    private static final String LAST_DURATION_MS_KEY = "lastDurationMs";
    private static final String REASON_KEY = "reason";

    @Getter
    private final boolean asyncEnabled;
    private final Duration checkTimeout;
    private final Duration refreshInterval;
    private final Duration staleTtl;

    private volatile HealthSnapshot snapshot = null;
    private final AtomicLong healthStartTimeMillis = new AtomicLong(-1L);

    protected AsyncHealthIndicator(AsyncHealthRefreshPolicy refreshPolicy) {
        this.asyncEnabled = refreshPolicy.isEnabled();
        this.checkTimeout = refreshPolicy.getHealthCheckTimeout();
        this.refreshInterval = refreshPolicy.getRefreshInterval();
        this.staleTtl = refreshPolicy.getStaleResultTtl();
    }

    protected abstract Health doHealthCheck() throws Exception;

    protected abstract Status getFailureHealthCheckStatus();

    @Override
    public final Health health() {
        return asyncEnabled ? healthAsync() : healthSync();
    }

    private Health healthSync() {
        try {
            return doHealthCheck();
        } catch (Exception e) {
            return Health.down()
                    .withDetail("error", e.getMessage())
                    .withDetail("errorType", e.getClass().getSimpleName())
                    .build();
        }
    }

    private Health healthAsync() {
        long startTime = this.healthStartTimeMillis.get();
        if (startTime != -1L) {
            long durationMs = System.currentTimeMillis() - startTime;
            if (durationMs > checkTimeout.toMillis()) {
                return Health.down()
                        .withDetail(REASON_KEY, "Timeout")
                        .withDetail(LAST_CHECKED_KEY, Instant.ofEpochMilli(startTime).toString())
                        .withDetail(LAST_DURATION_MS_KEY, durationMs)
                        .build();
            }
        }

        HealthSnapshot current = this.snapshot;
        if (current == null) {
            return Health.unknown()
                    .withDetail(REASON_KEY, "Initial health check pending")
                    .build();
        }

        long ageMillis = System.currentTimeMillis() - current.updatedMillis();
        if (ageMillis > staleTtl.toMillis()) {
            return Health.unknown()
                    .withDetail(REASON_KEY, "Health check result is stale")
                    .withDetail("cacheAge", Duration.ofMillis(ageMillis).toString())
                    .withDetail("staleTtl", staleTtl.toString())
                    .withDetail(LAST_CHECKED_KEY, Instant.ofEpochMilli(current.updatedMillis()).toString())
                    .build();
        }

        return current.health();
    }

    @Override
    public final void run() {
        long startTime = System.currentTimeMillis();
        this.healthStartTimeMillis.set(startTime);

        Health result;
        try {
            Health original = doHealthCheck();
            long duration = System.currentTimeMillis() - startTime;
            String checkTime = Instant.ofEpochMilli(startTime).toString();

            if (duration > checkTimeout.toMillis()) {
                result = Health.down()
                        .withDetail(REASON_KEY, "Timeout")
                        .withDetail(LAST_CHECKED_KEY, checkTime)
                        .withDetail(LAST_DURATION_MS_KEY, duration)
                        .build();
            } else {
                result = Health.status(original.getStatus())
                        .withDetails(original.getDetails())
                        .withDetail(LAST_CHECKED_KEY, checkTime)
                        .withDetail(LAST_DURATION_MS_KEY, duration)
                        .build();
            }
        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            result = onRunException(e, duration);
        } finally {
            this.healthStartTimeMillis.set(-1L);
        }

        this.snapshot = new HealthSnapshot(result, System.currentTimeMillis());
    }

    protected Health onRunException(Exception e, long durationMs) {
        return Health
                .status(getFailureHealthCheckStatus())
                .withDetail(REASON_KEY, "Exception")
                .withDetail("error", e.getMessage())
                .withDetail("errorType", e.getClass().getSimpleName())
                .withDetail(LAST_DURATION_MS_KEY, durationMs)
                .build();
    }

    @Override
    public Duration getRefreshInterval() {
        return refreshInterval;
    }

    @Override
    public String toString() {
        return getClass().getSimpleName()
                + "[async=" + asyncEnabled + "]"
                + "[refreshInterval=" + refreshInterval + "]"
                + "[checkTimeout=" + checkTimeout + "]"
                + "[staleTtl=" + staleTtl + "]";
    }

    private record HealthSnapshot(Health health, long updatedMillis) {
    }
}
