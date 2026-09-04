package space.br1440.platform.leaderelection.configuration;

import org.springframework.aop.framework.autoproxy.DefaultAdvisorAutoProxyCreator;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Role;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import space.br1440.platform.leaderelection.aspect.DistributedLockAspect;
import space.br1440.platform.leaderelection.execution.keepalive.KeepAliveLockFactory;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LockConfigurationValidationProcessor;
import space.br1440.platform.leaderelection.processor.LockHookProcessor;
import space.br1440.platform.leaderelection.schedulerlocking.SchedulerLockAdvisor;
import space.br1440.platform.leaderelection.schedulerlocking.configuration.LockConfigurationExtractor;

@AutoConfiguration(after = LeaderElectionAutoConfiguration.class)
@ConditionalOnProperty(name = LeaderElectionProperties.PREFIX + ".lock.enabled", havingValue = "true")
@EnableConfigurationProperties(LeaderElectionProperties.class)
public class LockAutoConfiguration {

    @Bean
    @Role(BeanDefinition.ROLE_INFRASTRUCTURE)
    @ConditionalOnClass(name = "org.springframework.scheduling.TaskScheduler")
    public static DefaultAdvisorAutoProxyCreator schedulerLockAutoProxyCreator() {
        DefaultAdvisorAutoProxyCreator creator = new DefaultAdvisorAutoProxyCreator();
        creator.setProxyTargetClass(true);
        creator.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return creator;
    }

    @Bean
    public LockConfigurationExtractor lockConfigurationExtractor(LeaderElectionProperties properties,
                                                                 Environment environment,
                                                                 ApplicationContext applicationContext) {
        return new LockConfigurationExtractor(properties, environment, applicationContext);
    }

    @Bean
    public static LockConfigurationValidationProcessor lockConfigurationValidationProcessor(LockConfigurationExtractor extractor) {
        return new LockConfigurationValidationProcessor(extractor);
    }

    @Bean
    public static LockHookProcessor lockHookProcessor() {
        return new LockHookProcessor();
    }

    @Bean
    @ConditionalOnMissingBean
    public KeepAliveLockFactory keepAliveLockFactory() {
        return new KeepAliveLockFactory();
    }

    @Bean
    public SchedulerLockAdvisor schedulerLockAdvisor(DistributedLockRegistry lockRegistry,
                                                     LockConfigurationExtractor lockConfigurationExtractor,
                                                     LockHookProcessor lockHookProcessor,
                                                     KeepAliveLockFactory keepAliveLockFactory,
                                                     LockConfigurationValidationProcessor validationProcessor) {
        return new SchedulerLockAdvisor(
                lockRegistry,
                lockConfigurationExtractor,
                lockHookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

    @Bean
    public DistributedLockAspect distributedLockAspect(DistributedLockRegistry lockRegistry,
                                                       LockConfigurationExtractor lockConfigurationExtractor,
                                                       LockHookProcessor lockHookProcessor,
                                                       KeepAliveLockFactory keepAliveLockFactory,
                                                       LockConfigurationValidationProcessor validationProcessor) {
        return new DistributedLockAspect(
                lockRegistry,
                lockConfigurationExtractor,
                lockHookProcessor,
                keepAliveLockFactory,
                validationProcessor
        );
    }

}
