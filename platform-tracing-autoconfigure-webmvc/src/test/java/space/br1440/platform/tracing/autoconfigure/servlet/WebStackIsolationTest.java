package space.br1440.platform.tracing.autoconfigure.servlet;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import space.br1440.platform.tracing.autoconfigure.TracingCoreAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.reactive.PlatformReactiveServerRequestObservationConvention;
import space.br1440.platform.tracing.autoconfigure.reactive.ReactiveTracingAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.reactive.TraceResponseHeaderWebFilter;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Контракт изоляции Servlet- и Reactive-веток платформенной трассировки после декомпозиции
 * autoconfigure на {@code platform-tracing-autoconfigure-webmvc} и
 * {@code platform-tracing-autoconfigure-webflux}.
 * <p>
 * В production-приложении тянется только один из двух web-модулей, но контракт
 * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication} должен
 * корректно работать даже если оба модуля случайно оказались в classpath (например, в
 * тестовых сценариях). Этот тест намеренно держит в classpath обе ветки и убеждается, что
 * web-бины поднимаются строго в соответствии с типом приложения.
 */
class WebStackIsolationTest {

    @Test
    void servletApplicationActivatesOnlyServletBeans() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingCoreAutoConfiguration.class,
                        ServletTracingAutoConfiguration.class,
                        ReactiveTracingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformServerRequestObservationConvention.class);
                    assertThat(context).hasBean("platformTraceResponseHeaderServletFilterRegistration");

                    assertThat(context).doesNotHaveBean(PlatformReactiveServerRequestObservationConvention.class);
                    assertThat(context).doesNotHaveBean(TraceResponseHeaderWebFilter.class);
                });
    }

    @Test
    void reactiveApplicationActivatesOnlyReactiveBeans() {
        new ReactiveWebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        TracingCoreAutoConfiguration.class,
                        ServletTracingAutoConfiguration.class,
                        ReactiveTracingAutoConfiguration.class))
                .run(context -> {
                    assertThat(context).hasSingleBean(PlatformReactiveServerRequestObservationConvention.class);
                    assertThat(context).hasSingleBean(TraceResponseHeaderWebFilter.class);

                    assertThat(context).doesNotHaveBean(PlatformServerRequestObservationConvention.class);
                    assertThat(context).doesNotHaveBean(TraceResponseHeaderServletFilter.class);
                });
    }
}
