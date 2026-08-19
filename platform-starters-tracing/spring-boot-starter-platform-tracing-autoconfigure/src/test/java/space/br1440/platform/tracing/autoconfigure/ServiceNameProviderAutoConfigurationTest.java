package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.identity.PlatformLocalServiceNameProvider;
import space.br1440.platform.tracing.autoconfigure.identity.ServiceNameProviderAutoConfiguration;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceNameProviderAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceNameProviderAutoConfiguration.class));

    @Test
    void providers_start_by_default() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformLocalServiceNameProvider.class);
        });
    }

    @Test
    void providers_start_even_when_tracing_enabled_false() {
        runner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PlatformLocalServiceNameProvider.class);
        });
    }

    @Test
    void local_provider_uses_spring_application_name() {
        runner.withPropertyValues("spring.application.name=order-service").run(context -> {
            PlatformLocalServiceNameProvider provider = context.getBean(PlatformLocalServiceNameProvider.class);
            assertThat(provider.get()).isEqualTo("order-service");
        });
    }

    @Test
    void local_provider_does_not_read_platform_tracing_service_name() {
        runner.withPropertyValues(
                "spring.application.name=spring-name",
                "platform.tracing.service.name=tracing-name"
        ).run(context -> {
            PlatformLocalServiceNameProvider provider = context.getBean(PlatformLocalServiceNameProvider.class);
            assertThat(provider.get()).isEqualTo("spring-name");
        });
    }
}
