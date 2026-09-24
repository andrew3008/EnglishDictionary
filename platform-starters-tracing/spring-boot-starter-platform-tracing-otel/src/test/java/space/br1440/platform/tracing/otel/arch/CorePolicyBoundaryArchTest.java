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
    @SuppressWarnings("unused")
    static final ArchRule coreDoesNotDependOnJmx =
            ModuleTaxonomyArchRules.CORE_MAIN_NO_JMX;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule applicationPlaneDoesNotDependOnAgentModule =
            ModuleTaxonomyArchRules.OTEL_DOES_NOT_DEPEND_ON_AGENT_NAMED_MODULE;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule remoteServiceNotConsumedByApplicationPlane =
            ModuleTaxonomyArchRules.REMOTE_SERVICE_NOT_CONSUMED_BY_APPLICATION_PLANE;
}
