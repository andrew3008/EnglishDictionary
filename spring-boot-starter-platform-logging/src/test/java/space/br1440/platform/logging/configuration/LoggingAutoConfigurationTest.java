package space.br1440.platform.logging.configuration;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.zalando.logbook.Logbook;

import static org.assertj.core.api.Assertions.assertThat;

class LoggingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(LoggingAutoConfiguration.class));

    @Test
    void loadsWithoutConsumerSupplyingLogbookDependency() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(Logbook.class);
        });
    }

    @Test
    void registersServletFilterByDefault() {
        contextRunner.run(context ->
                assertThat(context).hasSingleBean(FilterRegistrationBean.class));
    }

    @Test
    void servletFilterCanBeDisabledViaProperty() {
        contextRunner
                .withPropertyValues("platform.http-logging.servlet-filter.enabled=false")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).doesNotHaveBean(FilterRegistrationBean.class);
                    assertThat(context).hasSingleBean(Logbook.class);
                });
    }
}
