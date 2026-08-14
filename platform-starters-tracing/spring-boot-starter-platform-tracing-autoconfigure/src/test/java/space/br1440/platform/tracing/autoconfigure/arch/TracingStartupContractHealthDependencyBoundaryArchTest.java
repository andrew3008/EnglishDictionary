package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static space.br1440.platform.tracing.autoconfigure.arch.TracingStartupContractHealthDependencyRules.PRODUCTION_AUTOCONFIG_FQCN;

/**
 * Production-enforcement для пакета {@code diagnostics.health.startup}: Rule A/B/C/D применяются к
 * реальным main-классам (тестовые фикстуры отсечены {@link ImportOption.DoNotIncludeTests}). Доказательство
 * того, что сами правила действительно ловят нарушения, а не проходят из-за пустой выборки — в
 * {@link TracingStartupContractHealthDependencyBoundaryFixtureProofTest}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingStartupContractHealthDependencyBoundaryArchTest {

    @ArchTest
    static final ArchRule startupCoreDependencyBoundary =
            TracingStartupContractHealthDependencyRules.coreDependencyBoundary(PRODUCTION_AUTOCONFIG_FQCN);

    @ArchTest
    static final ArchRule startupAutoConfigurationDependencyBoundary =
            TracingStartupContractHealthDependencyRules.autoConfigurationDependencyBoundary(
                    PRODUCTION_AUTOCONFIG_FQCN);

    @ArchTest
    static final ArchRule startupPackageForbiddenTransportAndSdkDependencies =
            TracingStartupContractHealthDependencyRules.forbiddenTransportAndSdkDependencies();

    @ArchTest
    static final ArchRule startupCoreMustNotDependOnAutoConfiguration =
            TracingStartupContractHealthDependencyRules.coreMustNotDependOnAutoConfiguration(
                    PRODUCTION_AUTOCONFIG_FQCN);
}
