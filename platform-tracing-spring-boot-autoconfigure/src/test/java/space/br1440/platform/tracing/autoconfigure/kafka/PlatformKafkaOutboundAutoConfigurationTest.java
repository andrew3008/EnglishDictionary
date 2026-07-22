package space.br1440.platform.tracing.autoconfigure.kafka;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.propagation.PlatformOutboundPropagationAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.support.RequestIdentityBoundarySupport;
import space.br1440.platform.tracing.core.runtime.NoOpTracingRuntime;

class PlatformKafkaOutboundAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    PlatformKafkaOutboundAutoConfiguration.class,
                    PlatformOutboundPropagationAutoConfiguration.class))
            .withBean(TracingProperties.class, TracingProperties::new)
            .withBean(RequestIdentityBoundarySupport.class,
                    () -> new RequestIdentityBoundarySupport(NoOpTracingRuntime.noop()))
            .withPropertyValues("platform.tracing.kafka.propagate-platform-headers=true");

    @Test
    void registersProducerCustomizerAfterSharedPropagationBean() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(PlatformKafkaProducerFactoryCustomizer.class);
            assertThat(context).hasBean("platformOutboundPropagation");
        });
    }
}
