package space.br1440.platform.leaderelection.configuration;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.PositiveOrZero;
import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import space.br1440.platform.leaderelection.validation.configuration.annotation.DurationMax;
import space.br1440.platform.leaderelection.validation.configuration.annotation.DurationMin;

import java.time.Duration;

@Getter
@Setter
@Accessors(chain = true)
@Validated
@ConfigurationProperties(prefix = LeaderElectionProperties.PREFIX)
public class LeaderElectionProperties {

    public static final String PREFIX = "platform.leader-election";

    private static final String DEFAULT_REDIS_HOST = "localhost";
    private static final int DEFAULT_REDIS_PORT = 6379;
    private static final int DEFAULT_REDIS_DATABASE = 0;
    private static final String DEFAULT_REDIS_KEY_PREFIX = "platform:leader-election";
    private static final Duration DEFAULT_LEASE_DURATION = Duration.ofSeconds(15);
    private static final Duration DEFAULT_RENEW_DEADLINE = Duration.ofSeconds(10);
    private static final Duration DEFAULT_RETRY_PERIOD = Duration.ofSeconds(2);
    private static final Duration DEFAULT_LOCK_AT_MOST_FOR = Duration.ofMinutes(30);
    private static final Duration DEFAULT_LOCK_AT_LEAST_FOR = Duration.ofMinutes(1);
    private static final Duration DEFAULT_LOCK_EXPIRE_AFTER = Duration.ofMinutes(1);
    private static final Duration DEFAULT_LOCK_IDLE_BETWEEN_TRIES = Duration.ofMillis(50);

    @Valid
    private RedisSettings redis = new RedisSettings();
    
    @Valid
    private LeadershipSettings leadership = new LeadershipSettings();
    
    @Valid
    private LockSettings lock = new LockSettings();
    
    @Valid
    private MetricsSettings metrics = new MetricsSettings();

    public boolean isRedisRequired() {
        return leadership.isEnabled() || lock.isEnabled();
    }

    @AssertTrue(message = "lock-expire-after должен быть не менее 2x lease-duration для корректной обработки отказов. " +
                          "Текущее: lock-expire-after={lock.lockExpireAfter}, lease-duration={leadership.leaseDuration}. " +
                          "Рекомендуется: lock-expire-after >= {leadership.leaseDuration * 2}")
    public boolean isLockExpireAfterValid() {
        if (!leadership.isEnabled() || !lock.isEnabled()) {
            return true;
        }
        return lock.getLockExpireAfter().toMillis() >= leadership.getLeaseDuration().toMillis() * 2;
    }

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors(chain = true)
    public static class RedisSettings {
        private String host = DEFAULT_REDIS_HOST;

        @Min(value = 1, message = "Redis порт должен быть от 1 до 65535")
        @Max(value = 65535, message = "Redis порт должен быть от 1 до 65535")
        private int port = DEFAULT_REDIS_PORT;

        @PositiveOrZero(message = "Redis база данных должна быть неотрицательной (>= 0)")
        private int database = DEFAULT_REDIS_DATABASE;

        private String username;
        private String password;
        private String keyPrefix = DEFAULT_REDIS_KEY_PREFIX;

        @AssertTrue(message = "Redis host должен быть указан")
        public boolean isHostValid() {
            return org.springframework.util.StringUtils.hasText(host);
        }

        @AssertTrue(message = "Redis database должен быть от 0 до 15")
        public boolean isDatabaseValid() {
            return database >= 0 && database <= 15;
        }
    }

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors(chain = true)
    public static class LeadershipSettings {
        private boolean enabled = false;
        private String leadershipName;
        
        @DurationMin(seconds = 1, message = "lease-duration должен быть не менее 1 секунды")
        @DurationMax(minutes = 5, message = "lease-duration не должен превышать 5 минут")
        private Duration leaseDuration = DEFAULT_LEASE_DURATION;
        
        @DurationMin(seconds = 1, message = "renew-deadline должен быть не менее 1 секунды")
        @DurationMax(minutes = 5, message = "renew-deadline не должен превышать 5 минут")
        private Duration renewDeadline = DEFAULT_RENEW_DEADLINE;
        
        @DurationMin(millis = 100, message = "retry-period должен быть не менее 100 миллисекунд")
        @DurationMax(seconds = 60, message = "retry-period не должен превышать 60 секунд")
        private Duration retryPeriod = DEFAULT_RETRY_PERIOD;
        
        @AssertTrue(message = "renew-deadline должен быть меньше lease-duration. " +
                              "Рекомендуется: renew-deadline = lease-duration * 0.67. " +
                              "Текущее: renew-deadline={renewDeadline}, lease-duration={leaseDuration}")
        public boolean isRenewDeadlineValid() {
            if (!enabled) {
                return true;
            }

            return renewDeadline.toMillis() < leaseDuration.toMillis();
        }
        
        @AssertTrue(message = "renew-deadline должен быть приблизительно lease-duration * 0.67 для оптимальной производительности. " +
                              "Рекомендуется: {leaseDuration * 0.67}" +
                              "Текущее: renew-deadline={renewDeadline}, lease-duration={leaseDuration}, ")
        public boolean isRenewDeadlineOptimal() {
            if (!enabled) {
                return true;
            }

            long optimal = (long) (leaseDuration.toMillis() * 0.67);
            long actual = renewDeadline.toMillis();
            long tolerance = (long) (leaseDuration.toMillis() * 0.1);
            return Math.abs(actual - optimal) <= tolerance;
        }
        
        @AssertTrue(message = "retry-period должен быть приблизительно lease-duration * 0.17 для оптимальной производительности. " +
                              "Рекомендуется: {leaseDuration * 0.17}" +
                              "Текущее: retry-period={retryPeriod}, lease-duration={leaseDuration}, ")
        public boolean isRetryPeriodOptimal() {
            if (!enabled) {
                return true;
            }

            long optimal = (long) (leaseDuration.toMillis() * 0.17);
            long actual = retryPeriod.toMillis();
            long tolerance = (long) (leaseDuration.toMillis() * 0.1);
            return Math.abs(actual - optimal) <= tolerance;
        }
    }

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors(chain = true)
    public static class LockSettings {
        private boolean enabled = false;
        private Duration defaultLockAtMostFor = DEFAULT_LOCK_AT_MOST_FOR;
        private Duration defaultLockAtLeastFor = DEFAULT_LOCK_AT_LEAST_FOR;
        private Duration lockExpireAfter = DEFAULT_LOCK_EXPIRE_AFTER;
        private Duration idleBetweenTries = DEFAULT_LOCK_IDLE_BETWEEN_TRIES;
    }

    @Getter @Setter @AllArgsConstructor @NoArgsConstructor @Accessors(chain = true)
    public static class MetricsSettings {
        private boolean enabled = false;
    }

}
