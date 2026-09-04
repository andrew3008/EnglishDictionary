package space.br1440.platform.leaderelection.validation.configuration;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;

import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class LeaderElectionConfigurationValidator {

    private final LeaderElectionProperties properties;

    @EventListener(ApplicationReadyEvent.class)
    public void validateConfigurationOnStartup() {
        if (!properties.isRedisRequired()) {
            return;
        }

        validateLeadershipConfiguration();
        validateLockConfiguration();
        validateCrossConfiguration();
    }

    private void validateLeadershipConfiguration() {
        if (!properties.getLeadership().isEnabled()) {
            return;
        }

        LeaderElectionProperties.LeadershipSettings leadership = properties.getLeadership();

        long leaseDurationMs = leadership.getLeaseDuration().toMillis();
        long renewDeadlineMs = leadership.getRenewDeadline().toMillis();
        long retryPeriodMs = leadership.getRetryPeriod().toMillis();

        long optimalRenewDeadline = (long) (leaseDurationMs * 0.67);
        long optimalRetryPeriod = (long) (leaseDurationMs * 0.17);

        if (Math.abs(renewDeadlineMs - optimalRenewDeadline) > leaseDurationMs * 0.1) {
            log.warn("Platform LeaderElection конфигурация leadership: renew-deadline ({}мс) не оптимален. " +
                     "Рекомендуется: {}мс (lease-duration * 0.67). " +
                     "Текущее соотношение: {}",
                     renewDeadlineMs, optimalRenewDeadline,
                     String.format("%.2f", (double) renewDeadlineMs / leaseDurationMs)
            );
        }

        if (Math.abs(retryPeriodMs - optimalRetryPeriod) > leaseDurationMs * 0.1) {
            log.warn("Platform LeaderElection конфигурация leadership: retry-period ({}мс) не оптимален. " +
                     "Рекомендуется: {}мс (lease-duration * 0.17). " +
                     "Текущее соотношение: {}",
                     retryPeriodMs, optimalRetryPeriod,
                     String.format("%.2f", (double) retryPeriodMs / leaseDurationMs)
            );
        }

        if (renewDeadlineMs >= leaseDurationMs) {
            log.error("Platform LeaderElection конфигурация leadership: renew-deadline ({}мс) должен быть меньше lease-duration ({}мс). " +
                      "Лидер не сможет корректно продлевать lease!", renewDeadlineMs, leaseDurationMs);
        }
    }

    private void validateLockConfiguration() {
        if (!properties.getLock().isEnabled()) {
            return;
        }

        LeaderElectionProperties.LockSettings lock = properties.getLock();

        long lockExpireAfterMs = lock.getLockExpireAfter().toMillis();
        long defaultLockAtMostForMs = lock.getDefaultLockAtMostFor().toMillis();

        if (lockExpireAfterMs < defaultLockAtMostForMs) {
            log.warn("Platform LeaderElection конфигурация блокировок: lock-expire-after ({}мс) меньше default-lock-at-most-for ({}мс). " +
                     "Блокировки могут истечь до завершения задачи!", lockExpireAfterMs, defaultLockAtMostForMs);
        }
    }

    private void validateCrossConfiguration() {
        if (!properties.getLeadership().isEnabled() || !properties.getLock().isEnabled()) {
            return;
        }

        long leaseDurationMs = properties.getLeadership().getLeaseDuration().toMillis();
        long lockExpireAfterMs = properties.getLock().getLockExpireAfter().toMillis();
        long recommendedLockExpireAfter = leaseDurationMs * 2;

        if (lockExpireAfterMs < recommendedLockExpireAfter) {
            log.warn("Platform LeaderElection конфигурация: lock-expire-after ({}мс) меньше рекомендуемого ({}мс = lease-duration * 2). " +
                     "Это может привести к сбросу версий при переключении лидерства. " +
                     "Текущее соотношение: {}",
                     lockExpireAfterMs, recommendedLockExpireAfter,
                     String.format("%.2f", (double) lockExpireAfterMs / leaseDurationMs)
            );
        }
    }

}
