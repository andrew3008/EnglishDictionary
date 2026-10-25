package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.autoconfigure.endpoint.EndpointAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthContributorAutoConfiguration;
import org.springframework.boot.actuate.autoconfigure.health.HealthEndpointAutoConfiguration;
import org.springframework.boot.actuate.health.CompositeHealth;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthComponent;
import org.springframework.boot.actuate.health.HealthEndpoint;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.control.channel.TracingControlChannelAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.ControlledAgentTestRuntime;

class TracingStartupContractHealthComponentModelTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                            TracingControlChannelAutoConfiguration.class,
                            TracingRuntimeAutoConfiguration.class,
                            TracingStartupContractHealthAutoConfiguration.class,
                            EndpointAutoConfiguration.class,
                            HealthEndpointAutoConfiguration.class,
                            HealthContributorAutoConfiguration.class
                    )
            )
            .withPropertyValues(
                    "platform.tracing.control.channel=NONE",
                    "management.endpoint.health.enabled=true",
                    "management.endpoint.health.show-details=always"
            );

    @Test
    void componentModelExposesTracingStartupContractKeyAndNotLegacyTracingKey() {
        contextRunner
                .withInitializer(ControlledAgentTestRuntime::initializeWithExporter)
                .withPropertyValues("platform.tracing.enabled=true")
                .run(context -> {
                    HealthComponent root = context.getBean(HealthEndpoint.class).health();
                    assertThat(root.getStatus()).isEqualTo(Status.UP);
                    assertThat(root).isInstanceOf(CompositeHealth.class);

                    CompositeHealth composite = (CompositeHealth) root;
                    assertThat(composite.getComponents()).containsKey("tracingStartupContract");
                    assertThat(composite.getComponents()).doesNotContainKey("tracing");

                    HealthComponent tracingStartupContract = composite.getComponents().get("tracingStartupContract");
                    assertThat(tracingStartupContract.getStatus()).isEqualTo(Status.UP);
                    assertThat(tracingStartupContract).isInstanceOf(Health.class);
                    Health health = (Health) tracingStartupContract;
                    assertThat(health.getDetails()).containsEntry("reasonCode", "ENABLED_CONTRACT_SATISFIED");
                });
    }

    @Test
    void kebabCasePropertyDisablesContributor() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "management.health.tracing-startup-contract.enabled=false")
                .run(context -> assertThat(context).doesNotHaveBean("tracingStartupContractHealthIndicator"));
    }

    @Test
    void legacyTracingPropertyDoesNotDisableContributor() {
        contextRunner
                .withPropertyValues(
                        "platform.tracing.enabled=false",
                        "management.health.tracing.enabled=false")
                .run(context -> assertThat(context).hasBean("tracingStartupContractHealthIndicator"));
    }

    @Test
    void userSuppliedBeanWithSameNameBacksOffAutoConfiguration() {
        contextRunner
                .withPropertyValues("platform.tracing.enabled=false")
                .withUserConfiguration(CustomIndicatorConfiguration.class)
                .run(context -> assertThat(context.getBean("tracingStartupContractHealthIndicator"))
                        .isSameAs(CustomIndicatorConfiguration.CUSTOM_INSTANCE));
    }

    @Configuration
    static class CustomIndicatorConfiguration {

        static final HealthIndicator CUSTOM_INSTANCE = () -> Health.up().build();

        @Bean(name = "tracingStartupContractHealthIndicator")
        HealthIndicator tracingStartupContractHealthIndicator() {
            return CUSTOM_INSTANCE;
        }
    }
}
