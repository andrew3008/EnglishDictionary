package space.br1440.platform.leaderelection.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.autoconfigure.aop.AopAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import space.br1440.platform.leaderelection.aspect.DistributedLockAspect;
import space.br1440.platform.leaderelection.aspect.LeaderOnChangeAspect;
import space.br1440.platform.leaderelection.leaderelection.LeaderElectorInitiator;
import space.br1440.platform.leaderelection.leaderelection.model.LeaderRegistry;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.processor.LeaderAwareProcessor;
import space.br1440.platform.leaderelection.processor.LeaderOnChangeProcessor;
import space.br1440.platform.leaderelection.processor.OnLeaderGrantedProcessor;
import space.br1440.platform.leaderelection.processor.OnLeaderRevokedProcessor;
import space.br1440.platform.leaderelection.validation.configuration.LeaderElectionConfigurationValidator;

import static org.assertj.core.api.Assertions.assertThat;

class LeaderElectionEmptyProjectContextTest {

    private final ApplicationContextRunner emptyProject = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    LeaderElectionAutoConfiguration.class,
                    LeadershipAutoConfiguration.class,
                    LockAutoConfiguration.class,
                    RedisAutoConfiguration.class,
                    AopAutoConfiguration.class)
            );

    @Test
    void contextLoadsWhenStarterIsOnClasspathAndFeaturesAreDisabled() {
        emptyProject.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).doesNotHaveBean(LeaderElectionProperties.class);
            assertThat(context).doesNotHaveBean(DistributedLockRegistry.class);
        });
    }

    @Test
    void leadershipAnnotationProcessorsAreRegisteredWhenLeadershipIsEnabled() {
        emptyProject
                .withPropertyValues(
                        "platform.leader-election.leadership.enabled=true",
                        "platform.leader-election.leadership.leadership-name=test-leadership")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LeaderRegistry.class);
                    assertThat(context).hasSingleBean(LeaderAwareProcessor.class);
                    assertThat(context).hasSingleBean(LeaderOnChangeProcessor.class);
                    assertThat(context).hasSingleBean(OnLeaderGrantedProcessor.class);
                    assertThat(context).hasSingleBean(OnLeaderRevokedProcessor.class);
                    assertThat(context).hasSingleBean(LeaderOnChangeAspect.class);
                    assertThat(context).hasSingleBean(LeaderElectorInitiator.class);
                    assertThat(context).hasSingleBean(LeaderElectionConfigurationValidator.class);
                });
    }

    @Test
    void lockAnnotationAspectIsRegisteredWhenLockIsEnabled() {
        emptyProject
                .withPropertyValues("platform.leader-election.lock.enabled=true")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedLockAspect.class);
                    assertThat(context).hasSingleBean(LeaderElectionConfigurationValidator.class);
                });
    }

    @Test
    void leadershipAndLockAutoConfigurationAreIndividuallyExcludable() {
        new ApplicationContextRunner()
                .withUserConfiguration(AutoConfigurationImportTestApplication.class)
                .withPropertyValues(
                        "platform.leader-election.leadership.enabled=true",
                        "platform.leader-election.leadership.leadership-name=test-leadership",
                        "spring.autoconfigure.exclude=" + LeadershipAutoConfiguration.class.getName())
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(DistributedLockRegistry.class);
                    assertThat(context).doesNotHaveBean(LeaderRegistry.class);
                    assertThat(context).doesNotHaveBean(LeaderAwareProcessor.class);
                });
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class AutoConfigurationImportTestApplication {
    }
}
