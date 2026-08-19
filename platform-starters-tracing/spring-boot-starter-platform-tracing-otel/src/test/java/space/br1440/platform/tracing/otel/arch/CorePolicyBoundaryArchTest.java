package space.br1440.platform.tracing.otel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class CorePolicyBoundaryArchTest {

    @ArchTest
    static final ArchRule corePolicyDoesNotDependOnOpenTelemetryOrSpring =
            ModuleTaxonomyArchRules.CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING;

    @ArchTest
    static final ArchRule coreDoesNotDependOnJmx =
            ModuleTaxonomyArchRules.CORE_MAIN_NO_JMX;

    @ArchTest
    static final ArchRule applicationPlaneDoesNotDependOnAgentModule =
            ModuleTaxonomyArchRules.OTEL_DOES_NOT_DEPEND_ON_AGENT_NAMED_MODULE;
}
