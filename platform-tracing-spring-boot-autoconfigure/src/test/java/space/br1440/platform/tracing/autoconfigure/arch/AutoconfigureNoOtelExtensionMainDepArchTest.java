package space.br1440.platform.tracing.autoconfigure.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-1 guardrail: App CL autoconfigure must not depend on Agent CL otel-extension implementation.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class AutoconfigureNoOtelExtensionMainDepArchTest {

    @ArchTest
    static final ArchRule autoconfigureMainNoOtelExtensionImpl =
            ModuleTaxonomyArchRules.AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL;

    @ArchTest
    static final ArchRule appModulesNotDependOnCoreRuntimeVersioned =
            ModuleTaxonomyArchRules.APP_MODULES_NOT_DEPEND_ON_CORE_RUNTIME_VERSIONED;
}
