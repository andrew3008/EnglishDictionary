package space.br1440.platform.healthchecks.business;

import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.async.AsyncHealthIndicator;
import space.br1440.platform.healthchecks.async.AsyncHealthRefreshPolicy;
import space.br1440.platform.healthchecks.common.HealthProbe;
import space.br1440.platform.healthchecks.common.HealthStatuses;
import space.br1440.platform.healthchecks.common.ProbeAware;

import java.util.EnumSet;
import java.util.Set;

/**
 * Базовый класс для HealthCheck'ов, проверяющих бизнес-логику.
 *
 * <p>Пользователь возвращает Spring {@link Status} напрямую через
 * {@link BusinessHealthStatus#up()}, {@link BusinessHealthStatus#down(String)},
 * {@link BusinessHealthStatus#degraded(String)} — без промежуточных абстракций.
 *
 * <p>Наследование от {@link AsyncHealthIndicator}: бизнес-проверки часто включают
 * HTTP-вызовы к другим сервисам, проверки SLA, запросы к БД. Синхронное выполнение
 * в потоке Actuator при долгих ответах или таймаутах приводит к истечению
 * {@code timeoutSeconds} Kubernetes probe и каскадным рестартам. Async-режим
 * (при включённой политике) выполняет проверку в фоне с кэшированием результата.
 *
 * <p>Когда бизнес health check является опциональным, его DOWN статус не должен
 * тянуть общий статус /health c 503 HTTP Status Code. Для таких случаев нужно использовать
 * {@link BusinessHealthStatus#degraded(String)} или {@link BusinessHealthStatus#up(String)}
 * вместо {@link BusinessHealthStatus#down(String)}.
 * @see <a href="https://github.com/spring-projects/spring-boot/issues/25151">Spring Boot #25151 — Actuator: support optional downstream services in health</a>
 */
@Slf4j
public abstract class AbstractBusinessHealthIndicator extends AsyncHealthIndicator implements ProbeAware {

    private static final AsyncHealthRefreshPolicy DEFAULT_POLICY = new DefaultBusinessAsyncPolicy();

    protected AbstractBusinessHealthIndicator() {
        this(DEFAULT_POLICY);
    }

    protected AbstractBusinessHealthIndicator(AsyncHealthRefreshPolicy refreshPolicy) {
        super(refreshPolicy);
    }

    @Override
    public Set<HealthProbe> getProbes() {
        return EnumSet.of(HealthProbe.READINESS);
    }

    @Override
    protected final Health doHealthCheck() {
        BusinessHealthStatus status;
        try {
            status = executeHealthCheck();
        } catch (Exception e) {
            status = onError(e);
            log.warn("Business health check [{}] failed", getHealthCheckName(), e);
        }

        return buildHealth(status);
    }

    protected abstract String getHealthCheckName();

    protected abstract BusinessHealthStatus executeHealthCheck() throws Exception;

    /**
     * Для не опциональных Health Check'ов переопределить для того, чтобы возвращался Status.DOWN
     */
    @Override
    protected Status getFailureHealthCheckStatus() {
        return HealthStatuses.DEGRADED;
    }

    protected BusinessHealthStatus onError(Exception e) {
        BusinessHealthStatus.Builder builder = BusinessHealthStatus.builder()
                .status(getFailureHealthCheckStatus())
                .message("Health check failed")
                .detail("errorType", e.getClass().getSimpleName());
        if (e.getMessage() != null) {
            builder.detail("error", e.getMessage());
        }

        return builder.build();
    }

    protected Health buildHealth(BusinessHealthStatus status) {
        Status springStatus = status.getStatus();
        Health.Builder builder = Health.status(springStatus);

        builder.withDetails(status.getDetails())
                .withDetail("healthCheckName", getHealthCheckName())
                .withDetail("timestamp", status.getTimestamp().toString());

        if (status.getMessage() != null) {
            builder.withDetail("message", status.getMessage());
        }

        if (status.hasException()) {
            Exception ex = status.getException();
            builder.withDetail("errorType", ex.getClass().getSimpleName());
            if (ex.getMessage() != null) {
                builder.withDetail("error", ex.getMessage());
            }
        }

        if (HealthStatuses.DEGRADED.equals(springStatus) || Status.DOWN.equals(springStatus)) {
            builder.withDetail("alertRequired", true);
        }

        return builder.build();
    }
}
