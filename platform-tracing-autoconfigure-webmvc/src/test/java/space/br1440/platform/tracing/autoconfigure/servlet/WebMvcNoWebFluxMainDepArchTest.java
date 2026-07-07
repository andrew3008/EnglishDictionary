package space.br1440.platform.tracing.autoconfigure.servlet;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-1 guardrail: Servlet autoconfigure main sources must not depend on WebFlux/Reactor stack.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.servlet",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class WebMvcNoWebFluxMainDepArchTest {

    @ArchTest
    static final ArchRule webMvcMainNoWebFluxStack =
            ModuleTaxonomyArchRules.WEBMVC_MAIN_NO_WEBFLUX_STACK;
}
