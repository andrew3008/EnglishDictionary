package space.br1440.platform.tracing.autoconfigure;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.servicename.PlatformLocalServiceNameProvider;
import space.br1440.platform.tracing.autoconfigure.servicename.PlatformRemoteServiceNameProvider;

import static org.assertj.core.api.Assertions.assertThat;

class ServiceNameProviderAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(ServiceNameProviderAutoConfiguration.class));

    @Test
    void провайдеры_поднимаются_по_умолчанию() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(PlatformLocalServiceNameProvider.class);
            assertThat(context).hasSingleBean(PlatformRemoteServiceNameProvider.class);
        });
    }

    @Test
    void провайдеры_поднимаются_даже_при_tracing_enabled_false() {
        runner.withPropertyValues("platform.tracing.enabled=false").run(context -> {
            assertThat(context).hasSingleBean(PlatformLocalServiceNameProvider.class);
            assertThat(context).hasSingleBean(PlatformRemoteServiceNameProvider.class);
        });
    }

    @Test
    void local_provider_использует_spring_application_name() {
        runner.withPropertyValues("spring.application.name=order-service").run(context -> {
            PlatformLocalServiceNameProvider provider = context.getBean(PlatformLocalServiceNameProvider.class);
            assertThat(provider.get()).isEqualTo("order-service");
        });
    }

    @Test
    void local_provider_приоритет_у_platform_tracing_service_name() {
        runner.withPropertyValues(
                "spring.application.name=spring-name",
                "platform.tracing.service.name=tracing-name"
        ).run(context -> {
            PlatformLocalServiceNameProvider provider = context.getBean(PlatformLocalServiceNameProvider.class);
            assertThat(provider.get()).isEqualTo("tracing-name");
        });
    }
}
