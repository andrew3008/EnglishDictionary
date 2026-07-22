package space.br1440.platform.tracing.autoconfigure.reactive;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

/**
 * PR-1 guardrail: Reactive autoconfigure main sources must not depend on Servlet/MVC stack.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.reactive",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class WebFluxNoServletMainDepArchTest {

    @ArchTest
    static final ArchRule webFluxMainNoServletStack =
            ModuleTaxonomyArchRules.WEBFLUX_MAIN_NO_SERVLET_STACK;

    @ArchTest
    static final ArchRule webFluxMainNoCoreImplementation =
            ModuleTaxonomyArchRules.WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL;

    @ArchTest
    static final ArchRule webFluxMainNoImplementationDependency =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    static final ArchRule webFluxMainNoOtelContext = ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT;

    @ArchTest
    static final ArchRule webFluxMainNoInternalPropagationTypes =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES;

    @ArchTest
    static final ArchRule identityInternalTypesNotPublic =
            ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC;
}
