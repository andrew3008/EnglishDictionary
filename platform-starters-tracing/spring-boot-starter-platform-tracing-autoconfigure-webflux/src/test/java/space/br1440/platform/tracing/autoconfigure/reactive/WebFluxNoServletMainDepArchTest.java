package space.br1440.platform.tracing.autoconfigure.reactive;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.reactive",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class WebFluxNoServletMainDepArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webFluxMainNoServletStack =
            ModuleTaxonomyArchRules.WEBFLUX_MAIN_NO_SERVLET_STACK;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webFluxMainNoCoreImplementation =
            ModuleTaxonomyArchRules.WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webFluxMainNoImplementationDependency =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webFluxMainNoOtelContext = ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webFluxMainNoInternalPropagationTypes =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES;

}
