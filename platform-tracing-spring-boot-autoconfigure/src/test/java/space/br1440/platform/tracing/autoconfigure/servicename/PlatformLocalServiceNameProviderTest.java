package space.br1440.platform.tracing.autoconfigure.servicename;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformLocalServiceNameProviderTest {

    @Test
    void приоритет_TracingProperties_service_name() {
        TracingProperties properties = new TracingProperties();
        properties.getService().setName("override-service");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "spring-name");

        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(properties, env);

        assertThat(provider.get()).isEqualTo("override-service");
    }

    @Test
    void fallback_на_spring_application_name() {
        TracingProperties properties = new TracingProperties();
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "order-service");

        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(properties, env);

        assertThat(provider.get()).isEqualTo("order-service");
    }

    @Test
    void финальный_fallback_unknown_service() {
        TracingProperties properties = new TracingProperties();
        MockEnvironment env = new MockEnvironment();

        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(properties, env);

        assertThat(provider.get()).isEqualTo(PlatformLocalServiceNameProvider.UNKNOWN_SERVICE);
    }

    @Test
    void пустая_строка_в_TracingProperties_не_перекрывает_spring_имя() {
        TracingProperties properties = new TracingProperties();
        properties.getService().setName("   ");
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "billing-service");

        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(properties, env);

        assertThat(provider.get()).isEqualTo("billing-service");
    }

    @Test
    void устойчив_к_null_аргументам() {
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(null, null);

        assertThat(provider.get()).isEqualTo(PlatformLocalServiceNameProvider.UNKNOWN_SERVICE);
    }
}
