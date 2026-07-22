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

    @ArchTest
    static final ArchRule webMvcMainNoCoreImplementation =
            ModuleTaxonomyArchRules.WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL;

    @ArchTest
    static final ArchRule webMvcMainNoImplementationDependency =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    static final ArchRule webMvcMainNoOtelContext = ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT;

    @ArchTest
    static final ArchRule webMvcMainNoInternalPropagationTypes =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES;

    @ArchTest
    static final ArchRule identityInternalTypesNotPublic =
            ModuleTaxonomyArchRules.IDENTITY_INTERNAL_TYPES_NOT_PUBLIC;
}
