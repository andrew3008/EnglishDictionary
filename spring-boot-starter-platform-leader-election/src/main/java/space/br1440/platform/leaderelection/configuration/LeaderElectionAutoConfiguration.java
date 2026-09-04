package space.br1440.platform.leaderelection.configuration;

import java.time.Duration;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Conditional;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.RedisSerializer;

import space.br1440.platform.leaderelection.configuration.condition.RedisRequiredCondition;
import space.br1440.platform.leaderelection.configuration.redis.RedisConfigurationFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerAdapter;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetrics;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.validation.configuration.LeaderElectionConfigurationValidator;

import io.micrometer.core.instrument.MeterRegistry;

@AutoConfiguration
@EnableConfigurationProperties(LeaderElectionProperties.class)
@Conditional(RedisRequiredCondition.class)
@EnableAspectJAutoProxy(proxyTargetClass = true)
public class LeaderElectionAutoConfiguration {

    @Bean
    public LeaderElectionMetricsInterface leaderElectionMetrics(LeaderElectionProperties properties,
                                                                ObjectProvider<MeterRegistry> meterRegistryProvider) {
        if (!properties.getMetrics().isEnabled()) {
            return new NoOpLeaderElectionMetrics();
        }

        MeterRegistry meterRegistry = meterRegistryProvider.getIfAvailable();
        if (meterRegistry == null) {
            return new NoOpLeaderElectionMetrics();
        } else {
            return new LeaderElectionMetrics(meterRegistry);
        }
    }

    @Bean
    public StringRedisTemplate leaderElectionRedisTemplate(LeaderElectionProperties properties) {
        RedisConnectionFactory connectionFactory = RedisConfigurationFactory.createConnectionFactory(properties);
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.setKeySerializer(RedisSerializer.string());
        template.setValueSerializer(RedisSerializer.string());
        template.setHashKeySerializer(RedisSerializer.string());
        template.setHashValueSerializer(RedisSerializer.string());
        template.afterPropertiesSet();
        return template;
    }

    @Bean
    public CircuitBreakerAdapter leaderElectionCircuitBreaker(LeaderElectionProperties properties,
                                                              LeaderElectionMetricsInterface metrics) {
        boolean metricsEnabled = properties.getMetrics().isEnabled();
        String role = "leader-election";

        if (metricsEnabled) {
            return CircuitBreakerFactory.createWithMetrics(
                    5,
                    Duration.ofSeconds(30),
                    metrics,
                    role
            );
        } else {
            return CircuitBreakerFactory.create(
                    5,
                    Duration.ofSeconds(30)
            );
        }
    }

    @Bean
    public DistributedLockRegistry lockRegistry(LeaderElectionProperties properties,
                                                @Qualifier("leaderElectionRedisTemplate") StringRedisTemplate redisTemplate,
                                                LeaderElectionMetricsInterface metrics,
                                                CircuitBreakerAdapter circuitBreaker) {
        LeaderElectionProperties.RedisSettings redisSettings = properties.getRedis();
        LeaderElectionProperties.LockSettings lockSettings = properties.getLock();
        boolean metricsEnabled = properties.getMetrics().isEnabled();

        return new RedisDistributedLockRegistry(
                redisSettings.getKeyPrefix(),
                lockSettings.getLockExpireAfter(),
                lockSettings.getIdleBetweenTries(),
                redisTemplate,
                metrics,
                metricsEnabled,
                circuitBreaker
        );
    }

    @Bean
    public LeaderElectionConfigurationValidator leaderElectionConfigurationValidator(LeaderElectionProperties properties) {
        return new LeaderElectionConfigurationValidator(properties);
    }
}
