package space.br1440.platform.tracing.autoconfigure.arch;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.lang.ArchRule;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Негативное доказательство Rule A/B/C/D: три invalid-фикстуры физически лежат под тем же package
 * pattern, который используют production-правила ({@code ..autoconfigure.diagnostics.health.startup..}),
 * поэтому неизменённые правила действительно их выбирают. Без этого теста нельзя отличить "правило ловит
 * нарушения" от "правило проходит из-за пустой выборки".
 */
class TracingStartupContractHealthDependencyBoundaryFixtureProofTest {

    private static final String FIXTURE_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.archfixture.invalid";

    private static final String INVALID_AUTOCONFIG_FQCN = FIXTURE_PACKAGE
            + ".InvalidAutoConfigurationDependingOnJmxFacade";

    private static final JavaClasses FIXTURE_CLASSES = new ClassFileImporter().importPackages(FIXTURE_PACKAGE);

    @Test
    @DisplayName("Rule C ловит прямую зависимость startup-фикстуры на PlatformTracingJmxClient")
    void ruleCCatchesJmxClientDependency() {
        ArchRule rule = TracingStartupContractHealthDependencyRules.forbiddenTransportAndSdkDependencies();
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("DependsOnPlatformTracingJmxClient");
    }

    @Test
    @DisplayName("Rule A ловит зависимость startup-фикстуры вне java/Actuator health/своего пакета")
    void ruleACatchesOutOfAllowlistDependency() {
        ArchRule rule = TracingStartupContractHealthDependencyRules.coreDependencyBoundary(INVALID_AUTOCONFIG_FQCN);
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("DependsOnPlatformTracingJmxClient");
    }

    @Test
    @DisplayName("Rule B ловит нарушение allowlist подставной AutoConfiguration-фикстурой")
    void ruleBCatchesAllowlistViolationOnSubstitutedAutoConfiguration() {
        ArchRule rule = TracingStartupContractHealthDependencyRules
                .autoConfigurationDependencyBoundary(INVALID_AUTOCONFIG_FQCN);
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("InvalidAutoConfigurationDependingOnJmxFacade");
    }

    @Test
    @DisplayName("Rule D ловит обратное ребро startup-ядра на production AutoConfiguration")
    void ruleDCatchesCoreDependingBackOnAutoConfiguration() {
        ArchRule rule = TracingStartupContractHealthDependencyRules.coreMustNotDependOnAutoConfiguration(
                TracingStartupContractHealthDependencyRules.PRODUCTION_AUTOCONFIG_FQCN);
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("CoreDependingOnStartupAutoConfiguration");
    }
}
