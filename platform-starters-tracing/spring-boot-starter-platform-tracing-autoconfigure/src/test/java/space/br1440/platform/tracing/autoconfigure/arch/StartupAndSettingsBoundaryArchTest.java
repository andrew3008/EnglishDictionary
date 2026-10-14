package space.br1440.platform.tracing.autoconfigure.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static space.br1440.platform.tracing.autoconfigure.arch.TracingStartupContractHealthDependencyRules.PRODUCTION_AUTOCONFIG_FQCN;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class StartupAndSettingsBoundaryArchTest {

    private static final String SETTINGS_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure.control.settings";

    private static final String READ_PACKAGE = SETTINGS_PACKAGE + ".read..";

    private static final String DELIVERY_PACKAGE = SETTINGS_PACKAGE + ".delivery..";

    private static final String FIXTURE_PACKAGE =
            "space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.archfixture.invalid";

    private static final String INVALID_AUTOCONFIGURATION = FIXTURE_PACKAGE
            + ".InvalidAutoConfigurationDependingOnJmxFacade";

    private static final JavaClasses FIXTURE_CLASSES =
            new ClassFileImporter().importPackages(FIXTURE_PACKAGE);

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule startupCoreDependencyBoundary =
            TracingStartupContractHealthDependencyRules.coreDependencyBoundary(
                    PRODUCTION_AUTOCONFIG_FQCN);

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule startupAutoConfigurationDependencyBoundary =
            TracingStartupContractHealthDependencyRules.autoConfigurationDependencyBoundary(
                    PRODUCTION_AUTOCONFIG_FQCN);

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule startupForbiddenTransportAndSdkDependencies =
            TracingStartupContractHealthDependencyRules.forbiddenTransportAndSdkDependencies();

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule startupCoreDoesNotDependOnAutoConfiguration =
            TracingStartupContractHealthDependencyRules.coreMustNotDependOnAutoConfiguration(
                    PRODUCTION_AUTOCONFIG_FQCN);

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule settingsRootDoesNotDependOnChildContracts =
            noClasses()
                    .that().resideInAPackage(SETTINGS_PACKAGE)
                    .should().dependOnClassesThat().resideInAnyPackage(READ_PACKAGE, DELIVERY_PACKAGE)
                    .because("the settings root contains contracts shared by read and delivery");

    @Test
    void startupCoreBoundaryDetectsInvalidDependency() {
        // given
        ArchRule rule = TracingStartupContractHealthDependencyRules
                .coreDependencyBoundary(INVALID_AUTOCONFIGURATION);

        // when
        // then
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("DependsOnPlatformTracingJmxClient");
    }

    @Test
    void startupAutoConfigurationBoundaryDetectsInvalidDependency() {
        // given
        ArchRule rule = TracingStartupContractHealthDependencyRules
                .autoConfigurationDependencyBoundary(INVALID_AUTOCONFIGURATION);

        // when
        // then
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("InvalidAutoConfigurationDependingOnJmxFacade");
    }

    @Test
    void startupTransportBoundaryDetectsInvalidDependency() {
        // given
        ArchRule rule = TracingStartupContractHealthDependencyRules
                .forbiddenTransportAndSdkDependencies();

        // when
        // then
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("DependsOnPlatformTracingJmxClient");
    }

    @Test
    void startupDirectionBoundaryDetectsReverseDependency() {
        // given
        ArchRule rule = TracingStartupContractHealthDependencyRules
                .coreMustNotDependOnAutoConfiguration(PRODUCTION_AUTOCONFIG_FQCN);

        // when
        // then
        assertThatThrownBy(() -> rule.check(FIXTURE_CLASSES))
                .hasMessageContaining("CoreDependingOnStartupAutoConfiguration");
    }
}
