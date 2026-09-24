package space.br1440.platform.tracing.api.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.api",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PublicApiIsolationArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule apiDoesNotDependOnImplementationPackages =
            ModuleTaxonomyArchRules.API_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule apiDoesNotExposeOpenTelemetryOrFrameworkTypes =
            ModuleTaxonomyArchRules.API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule propagationContractsDoNotDependOnConcreteControlImplementation =
            ModuleTaxonomyArchRules.API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule identityImplementationTypesAreNotPublic =
            ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC;
}
