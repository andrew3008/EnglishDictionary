package space.br1440.platform.tracing.api.runtime.state.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * ArchUnit guardrails для {@code api.runtime.state} и запрета legacy {@code api.config}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.api",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RuntimeStateArchTest {

    @ArchTest
    static final ArchRule noApiConfigPackage = ModuleTaxonomyArchRules.NO_API_CONFIG_PACKAGE;
}
