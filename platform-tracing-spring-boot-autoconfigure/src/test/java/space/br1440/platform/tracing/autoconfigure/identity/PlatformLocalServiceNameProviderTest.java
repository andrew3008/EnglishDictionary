package space.br1440.platform.tracing.autoconfigure.identity;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformLocalServiceNameProviderTest {

    @Test
    void resolves_spring_application_name() {
        // given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "order-service");

        // when
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(env);

        // then
        assertThat(provider.get()).isEqualTo("order-service");
    }

    @Test
    void falls_back_to_unknown_service_when_spring_name_absent() {
        // given
        MockEnvironment env = new MockEnvironment();

        // when
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(env);

        // then
        assertThat(provider.get()).isEqualTo(PlatformLocalServiceNameProvider.UNKNOWN_SERVICE);
    }

    @Test
    void blank_spring_application_name_falls_back_to_unknown_service() {
        // given
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "   ");

        // when
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(env);

        // then
        assertThat(provider.get()).isEqualTo(PlatformLocalServiceNameProvider.UNKNOWN_SERVICE);
    }

    @Test
    void ignores_platform_tracing_service_name_spring_binding() {
        // given — agent-канал не читается этим провайдером
        MockEnvironment env = new MockEnvironment();
        env.setProperty("spring.application.name", "spring-name");
        env.setProperty("platform.tracing.service.name", "tracing-name");

        // when
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(env);

        // then
        assertThat(provider.get()).isEqualTo("spring-name");
    }

    @Test
    void tolerates_null_environment() {
        // when
        PlatformLocalServiceNameProvider provider = new PlatformLocalServiceNameProvider(null);

        // then
        assertThat(provider.get()).isEqualTo(PlatformLocalServiceNameProvider.UNKNOWN_SERVICE);
    }
}
