package space.br1440.platform.tracing.autoconfigure;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentStartupVerifier;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;

class TracingRuntimeGateOwnershipTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    TracingControlChannelAutoConfiguration.class,
                    TracingRuntimeAutoConfiguration.class
            ))
            .withPropertyValues(
                    "platform.tracing.enabled=false",
                    "platform.tracing.control.channel=NONE"
            );

    @Test
    void platform_owns_exactly_one_instance_of_each_startup_gate() {
        // given
        // when
        contextRunner.run(context -> {
            // then
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(AgentReadinessCoherenceGuard.class);
            assertThat(context).hasSingleBean(AgentStartupVerifier.class);
        });
    }

    @Test
    void application_owned_coherence_guard_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withBean(
                "customAgentReadinessCoherenceGuard",
                AgentReadinessCoherenceGuard.class,
                AgentReadinessCoherenceGuard::new
        );

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentReadinessCoherenceGuard bean is not supported "
                            + "with Controlled Agent ownership: customAgentReadinessCoherenceGuard"
            );
        });
    }

    @Test
    void application_owned_startup_verifier_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withBean(
                "customAgentStartupVerifier",
                AgentStartupVerifier.class,
                () -> new AgentStartupVerifier(new AgentReadinessCoherenceGuard())
        );

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentStartupVerifier bean is not supported "
                            + "with Controlled Agent ownership: customAgentStartupVerifier"
            );
        });
    }

    @Test
    void manually_registered_coherence_guard_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withInitializer(context ->
                context.getBeanFactory().registerSingleton(
                        "manualAgentReadinessCoherenceGuard",
                        new AgentReadinessCoherenceGuard()
                ));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentReadinessCoherenceGuard bean is not supported "
                            + "with Controlled Agent ownership: manualAgentReadinessCoherenceGuard"
            );
        });
    }

    @Test
    void manually_registered_startup_verifier_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withInitializer(context ->
                context.getBeanFactory().registerSingleton(
                        "manualAgentStartupVerifier",
                        new AgentStartupVerifier(new AgentReadinessCoherenceGuard())
                ));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentStartupVerifier bean is not supported "
                            + "with Controlled Agent ownership: manualAgentStartupVerifier"
            );
        });
    }

    @Test
    void manually_registered_coherence_guard_under_platform_name_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withInitializer(context ->
                context.addBeanFactoryPostProcessor(beanFactory ->
                        beanFactory.registerSingleton(
                                "agentReadinessCoherenceGuard",
                                new AgentReadinessCoherenceGuard()
                        )));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentReadinessCoherenceGuard bean is not supported "
                            + "with Controlled Agent ownership: agentReadinessCoherenceGuard"
            );
        });
    }

    @Test
    void manually_registered_startup_verifier_under_platform_name_is_rejected() {
        // given
        ApplicationContextRunner runner = contextRunner.withInitializer(context ->
                context.addBeanFactoryPostProcessor(beanFactory ->
                        beanFactory.registerSingleton(
                                "agentStartupVerifier",
                                new AgentStartupVerifier(new AgentReadinessCoherenceGuard())
                        )));

        // when
        runner.run(context -> {
            // then
            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasMessage(
                    "Application-owned AgentStartupVerifier bean is not supported "
                            + "with Controlled Agent ownership: agentStartupVerifier"
            );
        });
    }
}
