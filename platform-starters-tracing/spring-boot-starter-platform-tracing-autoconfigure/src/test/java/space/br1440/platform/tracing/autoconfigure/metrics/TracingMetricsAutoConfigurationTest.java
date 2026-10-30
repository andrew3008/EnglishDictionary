package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.assertj.AssertableApplicationContext;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class TracingMetricsAutoConfigurationTest {

    @Test
    void startup_fails_without_diagnostics_client() {
        // given
        ApplicationContextRunner contextRunner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(TracingMetricsAutoConfiguration.class))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

        // when / then
        contextRunner.run(context -> {
            assertThat(context).hasFailed();

            Throwable rootCause = rootCause(context);
            assertThat(rootCause).isInstanceOf(NoSuchBeanDefinitionException.class);

            String causeChain = causeChainMessages(context);
            assertThat(causeChain).contains(PlatformTracingJmxClient.class.getName());
        });
    }

    private static Throwable rootCause(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        assertThat(failure).isNotNull();

        Throwable cause = failure;
        while (cause.getCause() != null) {
            cause = cause.getCause();
        }
        return cause;
    }

    private static String causeChainMessages(AssertableApplicationContext context) {
        Throwable failure = context.getStartupFailure();
        StringBuilder messages = new StringBuilder();

        Throwable cause = failure;
        while (cause != null) {
            messages.append(cause).append(" / ");
            cause = cause.getCause();
        }
        return messages.toString();
    }
}
