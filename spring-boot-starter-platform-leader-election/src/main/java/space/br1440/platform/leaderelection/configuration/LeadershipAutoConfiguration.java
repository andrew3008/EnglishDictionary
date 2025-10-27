package space.br1440.platform.leaderelection.configuration;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.leaderelection.LeaderElector;
import space.br1440.platform.leaderelection.leaderelection.LeaderElectorInitiator;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;

@Configuration
@ConditionalOnProperty(name = LeaderElectionProperties.PREFIX + ".leadership.enabled", havingValue = "true")
@EnableConfigurationProperties(LeaderElectionProperties.class)
class LeadershipAutoConfiguration {

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

}
