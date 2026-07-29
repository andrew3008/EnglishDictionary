package space.br1440.platform.tracing.autoconfigure.reactive;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.assertj.AssertableReactiveWebApplicationContext;
import org.springframework.boot.test.context.assertj.AssertableWebApplicationContext;
import org.springframework.boot.test.context.runner.ReactiveWebApplicationContextRunner;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.reactive.DispatcherHandler;
import org.springframework.web.reactive.function.server.RouterFunction;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * P4-WF.6 — physical absence of the WebFlux management write surface for every
 * non-mutating posture and for non-reactive / missing-WebFlux classpaths.
 */
class TracingControlWebFluxManagementContextAbsenceTest {

    private final ReactiveWebApplicationContextRunner reactiveRunner =
            new ReactiveWebApplicationContextRunner()
                    .withUserConfiguration(TracingControlWebFluxManagementContextConfiguration.class);

    private final WebApplicationContextRunner servletRunner =
            new WebApplicationContextRunner()
                    .withUserConfiguration(TracingControlWebFluxManagementContextConfiguration.class);

    @Test
    void helmLocal_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL")
                .run(this::assertAbsent);
    }

    @Test
    void configServerLocal_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=CONFIG_SERVER",
                        "platform.environment=LOCAL")
                .run(this::assertAbsent);
    }

    @Test
    void actuatorShared_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=SHARED")
                .run(this::assertAbsent);
    }

    @Test
    void actuatorProduction_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=PRODUCTION")
                .run(this::assertAbsent);
    }

    @Test
    void disabledAuthority_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.mode=DISABLED",
                        "platform.tracing.configuration.authority=HELM",
                        "platform.environment=LOCAL")
                .run(this::assertAbsent);
    }

    @Test
    void actuatorLocalOnServletWebApplication_physicallyAbsent() {
        servletRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=LOCAL")
                .run(this::assertAbsentOnServlet);
    }

    @Test
    void actuatorLocalWithoutDispatcherHandler_physicallyAbsent() {
        reactiveRunner.withPropertyValues(
                        "platform.tracing.configuration.authority=ACTUATOR",
                        "platform.environment=LOCAL")
                .withClassLoader(new FilteredClassLoader(DispatcherHandler.class))
                .run(this::assertAbsent);
    }

    private void assertAbsent(AssertableReactiveWebApplicationContext context) {
        assertThat(context).doesNotHaveBean(TracingControlWebFluxManagementContextConfiguration.class);
        assertThat(context).doesNotHaveBean("tracingControlWebFluxRoutes");
        assertThat(context.getBeansOfType(RouterFunction.class)).isEmpty();
    }

    private void assertAbsentOnServlet(AssertableWebApplicationContext context) {
        assertThat(context).doesNotHaveBean(TracingControlWebFluxManagementContextConfiguration.class);
        assertThat(context).doesNotHaveBean("tracingControlWebFluxRoutes");
        assertThat(context.getBeansOfType(RouterFunction.class)).isEmpty();
    }
}
