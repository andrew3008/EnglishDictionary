package space.br1440.platform.leaderelection.configuration;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.core.env.Environment;

import space.br1440.platform.leaderelection.aspect.LeaderOnChangeAspect;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.leaderelection.LeaderElector;
import space.br1440.platform.leaderelection.leaderelection.LeaderElectorInitiator;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderRegistry;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.processor.LeaderAwareProcessor;
import space.br1440.platform.leaderelection.processor.LeaderOnChangeProcessor;
import space.br1440.platform.leaderelection.processor.OnLeaderGrantedProcessor;
import space.br1440.platform.leaderelection.processor.OnLeaderRevokedProcessor;
import space.br1440.platform.leaderelection.processor.executor.LeaderChangeTaskExecutor;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;

@AutoConfiguration(after = LeaderElectionAutoConfiguration.class)
@ConditionalOnProperty(name = LeaderElectionProperties.PREFIX + ".leadership.enabled", havingValue = "true")
@EnableConfigurationProperties(LeaderElectionProperties.class)
public class LeadershipAutoConfiguration {

    @Bean
    public LeadershipEventPublisher leadershipEventPublisher(LeaderElectionProperties properties,
                                                             Environment environment,
                                                             ApplicationEventPublisher eventPublisher) {
        String leadershipName = LeadershipNameResolver.resolve(properties, environment);
        return new LeadershipEventPublisher(this, leadershipName, eventPublisher);
    }

    @Bean
    public LeaderElector leaderElector(LeaderElectionProperties properties,
                                       Environment environment,
                                       DistributedLockRegistry lockRegistry,
                                       LeadershipEventPublisher eventPublisher,
                                       LeaderElectionMetricsInterface metrics) {
        return new LeaderElector(properties, environment, lockRegistry, eventPublisher, metrics);
    }

    @Bean
    public LeaderElectorInitiator leaderElectorInitiator(LeaderElectionProperties properties,
                                                         Environment environment,
                                                         LeaderElector leaderElector) {
        return new LeaderElectorInitiator(properties, environment, leaderElector);
    }

    @Bean
    public LeaderRegistry leaderRegistry() {
        return new LeaderRegistry();
    }

    @Bean
    public static LeaderAwareProcessor leaderAwareProcessor(LeaderRegistry leaderRegistry,
                                                             Environment environment,
                                                             LeaderElectionProperties properties) {
        return new LeaderAwareProcessor(leaderRegistry, environment, properties);
    }

    @Bean
    public LeaderOnChangeProcessor leaderOnChangeProcessor(ApplicationContext applicationContext,
                                                           Environment environment,
                                                           LeaderElectionProperties properties) {
        return new LeaderOnChangeProcessor(applicationContext, environment, properties);
    }

    @Bean
    public OnLeaderGrantedProcessor onLeaderGrantedProcessor(ApplicationContext applicationContext,
                                                             Environment environment,
                                                             LeaderElectionProperties properties) {
        return new OnLeaderGrantedProcessor(applicationContext, environment, properties);
    }

    @Bean
    public OnLeaderRevokedProcessor onLeaderRevokedProcessor(ApplicationContext applicationContext,
                                                             Environment environment,
                                                             LeaderElectionProperties properties) {
        return new OnLeaderRevokedProcessor(applicationContext, environment, properties);
    }

    @Bean
    public LeaderChangeTaskExecutor leaderChangeTaskExecutor() {
        return new LeaderChangeTaskExecutor();
    }

    @Bean
    public LeaderOnChangeAspect leaderOnChangeAspect(LeaderChangeTaskExecutor leaderChangeTaskExecutor) {
        return new LeaderOnChangeAspect(leaderChangeTaskExecutor);
    }

}
