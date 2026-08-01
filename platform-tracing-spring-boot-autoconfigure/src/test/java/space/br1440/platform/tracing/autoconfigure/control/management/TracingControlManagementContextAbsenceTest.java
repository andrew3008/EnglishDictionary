package space.br1440.platform.tracing.autoconfigure.control.management;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import space.br1440.platform.tracing.autoconfigure.servlet.control.TracingControlManagementContextConfiguration;
import space.br1440.platform.tracing.autoconfigure.servlet.control.TracingControlMvcController;
import space.br1440.platform.tracing.autoconfigure.support.FilteredClassLoader;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves physical absence of {@link TracingControlMvcController} /
 * {@link TracingControlManagementContextConfiguration} for every combination the P4
 * fail-fast matrix requires to be non-mutating. Each scenario boots a real (minimal)
 * Spring context and evaluates the actual {@code @ConditionalOnClass} /
 * {@code @ConditionalOnProperty} conditions declared on
 * {@link TracingControlManagementContextConfiguration} — not a mock, not a hand-written
 * assertion about the annotations. {@link ApplicationContextRunner} is used instead of a
 * full two-port {@link org.springframework.boot.SpringApplication} boot because these are
 * pure absence proofs: no mutating bean, controller, or route can be reached regardless of
 * which context type wraps the same condition evaluation, and the full-boot proof for the
 * one case where the surface IS present (ACTUATOR+LOCAL) is already covered by
 * {@link TracingControlManagementChildContextIntegrationTest}.
 */
class TracingControlManagementContextAbsenceTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(TracingControlManagementContextConfiguration.class);

    @Test
    void helmLocal_physicallyAbsent() {
        runner.withPropertyValues(
                "platform.tracing.configuration.authority=HELM",
                "platform.environment=LOCAL")
                .run(this::assertAbsent);
    }

    @Test
    void configServerLocal_physicallyAbsent() {
        runner.withPropertyValues(
                "platform.tracing.configuration.authority=CONFIG_SERVER",
                "platform.environment=LOCAL")
                .run(this::assertAbsent);
    }

    @Test
    void actuatorShared_physicallyAbsent() {
        runner.withPropertyValues(
                "platform.tracing.configuration.authority=ACTUATOR",
                "platform.environment=SHARED")
                .run(this::assertAbsent);
    }

    @Test
    void actuatorProduction_physicallyAbsent() {
        runner.withPropertyValues(
                "platform.tracing.configuration.authority=ACTUATOR",
                "platform.environment=PRODUCTION")
                .run(this::assertAbsent);
    }

    /**
     * {@code ACTUATOR + LOCAL} is the sole combination where the write surface must be
     * present per the fail-fast matrix — but only on a Servlet classpath. This proves the
     * current Servlet-only implementation correctly self-deactivates (rather than failing
     * unpredictably) when {@code WebMvcConfigurer} is absent, per
     * {@code @ConditionalOnClass(WebMvcConfigurer.class)}. See
     * {@code docs/research/evidence/servlet-webflux-management-control/decision-summary.json}
     * for the committee-pending decision record this proves.
     */
    @Test
    void actuatorLocalWithoutWebMvc_physicallyAbsent() {
        runner.withPropertyValues(
                "platform.tracing.configuration.authority=ACTUATOR",
                "platform.environment=LOCAL")
                .withClassLoader(new FilteredClassLoader(
                        getClass().getClassLoader(),
                        Set.of("org.springframework.web.servlet")))
                .run(this::assertAbsent);
    }

    private void assertAbsent(org.springframework.boot.test.context.assertj.AssertableApplicationContext context) {
        assertThat(context).doesNotHaveBean(TracingControlManagementContextConfiguration.class);
        assertThat(context).doesNotHaveBean(TracingControlMvcController.class);
        assertThat(context).doesNotHaveBean("tracingControlMvcController");
        assertThat(context).doesNotHaveBean("tracingControlPathPrefixConfigurer");
    }
}
