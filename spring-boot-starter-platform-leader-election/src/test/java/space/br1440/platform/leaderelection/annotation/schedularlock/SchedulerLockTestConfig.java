package space.br1440.platform.leaderelection.annotation.schedularlock;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.DependsOn;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.context.annotation.Primary;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.execution.LockingTaskExecutor;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.RedisDistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.redis.resilience.CircuitBreakerFactory;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.metrics.NoOpLeaderElectionMetrics;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.SchedulerLockAdvisor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;
import space.br1440.platform.leaderelection.testutil.extension.container.EmbeddedRedisServer;

import java.time.Duration;

@TestConfiguration
@EnableScheduling
@EnableAspectJAutoProxy
class SchedulerLockTestConfig {

    @Bean
    public EmbeddedRedisServer embeddedRedisServer() {
        return new EmbeddedRedisServer(0);
    }

    @Bean
    @Primary
    @DependsOn("embeddedRedisServer")
    public StringRedisTemplate testStringRedisTemplate(EmbeddedRedisServer redisServer) {
        if (!redisServer.isRunning()) {
            redisServer.start();
        }

        return redisServer.createStringRedisTemplate();
    }

    @Bean
    @Primary
    @DependsOn("embeddedRedisServer")
    public RedisConnectionFactory testRedisConnectionFactory(EmbeddedRedisServer redisServer) {
        if (!redisServer.isRunning()) {
            redisServer.start();
        }

        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(redisServer.getHost(), redisServer.getPort());
        connectionFactory.afterPropertiesSet();
        return connectionFactory;
    }

    @Bean
    @Primary
    public LeaderElectionMetricsInterface testLeaderElectionMetricsInterface() {
        return new NoOpLeaderElectionMetrics();
    }

    @Bean
    @Primary
    public ObjectMapper testObjectMapper() {
        return new ObjectMapper();
    }

    @Bean
    @Primary
    public LeaderElectionProperties testLeaderElectionProperties() {
        LeaderElectionProperties properties = new LeaderElectionProperties();

        LeaderElectionProperties.RedisSettings redisSettings = new LeaderElectionProperties.RedisSettings();
        redisSettings.setKeyPrefix("test");
        redisSettings.setHost("localhost");
        redisSettings.setPort(6379);
        redisSettings.setDatabase(0);
        properties.setRedis(redisSettings);

        LeaderElectionProperties.LockSettings lockSettings = new LeaderElectionProperties.LockSettings();
        lockSettings.setEnabled(true);
        lockSettings.setDefaultLockAtMostFor(Duration.ofMinutes(5));
        lockSettings.setDefaultLockAtLeastFor(Duration.ofSeconds(1));
        lockSettings.setLockExpireAfter(Duration.ofMinutes(1));
        lockSettings.setIdleBetweenTries(Duration.ofMillis(50));
        properties.setLock(lockSettings);

        LeaderElectionProperties.LeadershipSettings leadershipSettings = new LeaderElectionProperties.LeadershipSettings();
        leadershipSettings.setEnabled(false);
        properties.setLeadership(leadershipSettings);

        return properties;
    }

    @Bean
    @Primary
    public DistributedLockRegistry testDistributedLockRegistry(StringRedisTemplate redisTemplate,
                                                               LeaderElectionMetricsInterface metrics,
                                                               LeaderElectionProperties properties) {
        LeaderElectionProperties.LockSettings lockSettings = properties.getLock();
        LeaderElectionProperties.RedisSettings redisSettings = properties.getRedis();

        return new RedisDistributedLockRegistry(
                redisSettings.getKeyPrefix(),
                lockSettings.getLockExpireAfter(),
                lockSettings.getIdleBetweenTries(),
                redisTemplate,
                metrics,
                false,                
                CircuitBreakerFactory.create(5, Duration.ofSeconds(30))
        );
    }

    @Bean
    @Primary
    public LockConfigurationExtractor testLockConfigurationExtractor(LeaderElectionProperties properties,
                                                                     Environment environment,
                                                                     ApplicationContext applicationContext) {
        return new LockConfigurationExtractor(properties, environment, applicationContext);
    }

    @Bean
    @Primary
    public LockHookProcessor testLockHookProcessor() {
        return new LockHookProcessor();
    }

    @Bean
    @Primary
    public LockConfigurationValidationProcessor testLockConfigurationValidationProcessor(LockConfigurationExtractor lockConfigurationExtractor) {
        return new LockConfigurationValidationProcessor(lockConfigurationExtractor);
    }

    @Bean
    @ConditionalOnMissingBean
    public KeepAliveLockFactory keepAliveLockFactory() {
        return new KeepAliveLockFactory();
    }

    @Bean
    @Primary
    public LockingTaskExecutor testLockingTaskExecutor(LockConfigurationExtractor lockConfigurationExtractor,
                                                       LockHookProcessor lockHookProcessor,
                                                       LockConfigurationValidationProcessor lockConfigurationValidationProcessor,
                                                       KeepAliveLockFactory keepAliveLockFactory) {
        return new LockingTaskExecutor(lockConfigurationExtractor, lockHookProcessor, keepAliveLockFactory, lockConfigurationValidationProcessor);
    }

    @Bean
    @Primary
    public SchedulerLockAdvisor testSchedulerLockAdvisor(DistributedLockRegistry distributedLockRegistry,
                                                         LockConfigurationExtractor lockConfigurationExtractor,
                                                         LockHookProcessor lockHookProcessor,
                                                         KeepAliveLockFactory keepAliveLockFactory,
                                                         LockConfigurationValidationProcessor lockConfigurationValidationProcessor) {
        return new SchedulerLockAdvisor(
                distributedLockRegistry,
                lockConfigurationExtractor,
                lockHookProcessor,
                keepAliveLockFactory,
                lockConfigurationValidationProcessor
        );
    }

    @Bean
    @Primary
    public TaskScheduler testTaskScheduler() {
        ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
        scheduler.setPoolSize(2);
        scheduler.setThreadNamePrefix("test-scheduler-");
        scheduler.setWaitForTasksToCompleteOnShutdown(true);
        scheduler.setAwaitTerminationSeconds(20);
        scheduler.initialize();
        return scheduler;
    }

    @Bean
    public DefaultAdvisorAutoProxyCreator schedulerLockAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator autoProxyCreator = new DefaultAdvisorAutoProxyCreator();
        autoProxyCreator.setProxyTargetClass(true);
        autoProxyCreator.setUsePrefix(true);
        return autoProxyCreator;
    }

}
