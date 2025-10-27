package space.br1440.platform.leaderelection.testutil.mock;

import org.mockito.Mockito;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import space.br1440.platform.leaderelection.aspect.DistributedLockAspect;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.leaderelection.LeaderElector;
import space.br1440.platform.leaderelection.leaderelection.LeaderElectorInitiator;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.SchedulerLockAdvisor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

public class LeaderElectionMockContext implements AutoCloseable {

    private final Map<Class<?>, Object> mockBeans = new HashMap<>();
    private final RedisConnectionFactory realConnectionFactory;

    public LeaderElectionMockContext(RedisConnectionFactory realConnectionFactory) {
        this.realConnectionFactory = realConnectionFactory;
        initializeMocks();
    }

    private void initializeMocks() {
        mockBeans.put(LeaderElectionProperties.class, Mockito.mock(LeaderElectionProperties.class));
        mockBeans.put(Environment.class, Mockito.mock(Environment.class));
        mockBeans.put(ApplicationContext.class, Mockito.mock(ApplicationContext.class));
        mockBeans.put(ApplicationEventPublisher.class, Mockito.mock(ApplicationEventPublisher.class));

        if (realConnectionFactory != null) {
            mockBeans.put(RedisConnectionFactory.class, realConnectionFactory);
            StringRedisTemplate stringRedisTemplate = new StringRedisTemplate(realConnectionFactory);
            mockBeans.put(StringRedisTemplate.class, stringRedisTemplate);
        } else {
            mockBeans.put(RedisConnectionFactory.class, Mockito.mock(RedisConnectionFactory.class));
            mockBeans.put(StringRedisTemplate.class, Mockito.mock(StringRedisTemplate.class));
        }

        mockBeans.put(LeaderElectionMetricsInterface.class, Mockito.mock(LeaderElectionMetricsInterface.class));

        mockBeans.put(DistributedLockRegistry.class, Mockito.mock(DistributedLockRegistry.class));
        mockBeans.put(DistributedLock.class, Mockito.mock(DistributedLock.class));

        mockBeans.put(LeadershipEventPublisher.class, Mockito.mock(LeadershipEventPublisher.class));
        mockBeans.put(LeaderElector.class, Mockito.mock(LeaderElector.class));
        mockBeans.put(LeaderElectorInitiator.class, Mockito.mock(LeaderElectorInitiator.class));

        mockBeans.put(LockConfigurationExtractor.class, Mockito.mock(LockConfigurationExtractor.class));
        mockBeans.put(LockHookProcessor.class, Mockito.mock(LockHookProcessor.class));

        mockBeans.put(SchedulerLockAdvisor.class, Mockito.mock(SchedulerLockAdvisor.class));
        mockBeans.put(DistributedLockAspect.class, Mockito.mock(DistributedLockAspect.class));
    }

    @SuppressWarnings("unchecked")
    public <T> T getBean(Class<T> type) {
        Object bean = mockBeans.get(type);
        if (bean == null) {
            throw new IllegalArgumentException("No mock bean found for type: " + type.getName());
        }
        return (T) bean;
    }

    public LeaderElectionMetricsInterface getMetrics() {
        return getBean(LeaderElectionMetricsInterface.class);
    }

    public void resetAllMocks() {
        mockBeans.values().forEach(bean -> {
            if (Mockito.mockingDetails(bean).isMock()) {
                Mockito.reset(bean);
            }
        });
    }

    public void configureDefaultBehaviors() {
        LeaderElectionProperties props = getBean(LeaderElectionProperties.class);
        if (Mockito.mockingDetails(props).isMock()) {
            LeaderElectionProperties.LeadershipSettings leadershipSettings = new LeaderElectionProperties.LeadershipSettings();
            leadershipSettings.setEnabled(true);
            leadershipSettings.setLeadershipName("test-leadership");
            leadershipSettings.setLeaseDuration(Duration.ofSeconds(15));
            leadershipSettings.setRenewDeadline(Duration.ofSeconds(10));
            leadershipSettings.setRetryPeriod(Duration.ofSeconds(2));
            Mockito.when(props.getLeadership()).thenReturn(leadershipSettings);

            LeaderElectionProperties.LockSettings lockSettings = new LeaderElectionProperties.LockSettings();
            lockSettings.setEnabled(true);
            Mockito.when(props.getLock()).thenReturn(lockSettings);
        }

        Environment env = getBean(Environment.class);
        if (Mockito.mockingDetails(env).isMock()) {
            Mockito.when(env.getProperty("POD_NAME")).thenReturn("instance-1");
        }

        DistributedLockRegistry lockRegistry = getBean(DistributedLockRegistry.class);
        DistributedLock distributedLock = getBean(DistributedLock.class);
        if (Mockito.mockingDetails(lockRegistry).isMock()) {
            Mockito.when(lockRegistry.obtain(Mockito.anyString())).thenReturn(distributedLock);
        }
    }

    @Override
    public void close() {
        mockBeans.clear();
    }

}
