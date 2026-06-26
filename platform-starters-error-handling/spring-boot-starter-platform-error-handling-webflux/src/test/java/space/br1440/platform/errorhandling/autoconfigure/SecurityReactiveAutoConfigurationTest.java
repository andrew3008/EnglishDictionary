package space.br1440.platform.errorhandling.autoconfigure;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.jackson.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import space.br1440.platform.errorhandling.core.security.ReactiveSecurityProblemSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("SecurityReactiveAutoConfiguration")
class SecurityReactiveAutoConfigurationTest {

    private final ReactiveWebApplicationContextRunner contextRunner =
            new ReactiveWebApplicationContextRunner()
                    .withConfiguration(AutoConfigurations.of(
                            JacksonAutoConfiguration.class,
                            ErrorHandlingCoreAutoConfiguration.class,
                            ErrorHandlingWebFluxAutoConfiguration.class,
                            SecurityReactiveAutoConfiguration.class));

    @Test
    @DisplayName("Spring Security на classpath — регистрирует ReactiveSecurityProblemSupport")
    void securityOnClasspath_registersSupport() {
        contextRunner.run(ctx ->
                assertThat(ctx).hasSingleBean(ReactiveSecurityProblemSupport.class));
    }

    @Test
    @DisplayName("Custom support bean предотвращает авто-регистрацию")
    void customSupportBean_preventsDefault() {
        // given
        contextRunner
                .withBean("reactiveSecurityProblemSupport",
                        ReactiveSecurityProblemSupport.class, () -> null)
                .run(ctx -> {
                    // when / then
                    assertThat(ctx).hasBean("reactiveSecurityProblemSupport");
                });
    }
}
