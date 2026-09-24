package space.br1440.platform.tracing.autoconfigure.servlet;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure.servlet",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class WebMvcNoWebFluxMainDepArchTest {

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webMvcMainNoWebFluxStack =
            ModuleTaxonomyArchRules.WEBMVC_MAIN_NO_WEBFLUX_STACK;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webMvcMainNoCoreImplementation =
            ModuleTaxonomyArchRules.WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webMvcMainNoImplementationDependency =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webMvcMainNoOtelContext = ModuleTaxonomyArchRules.WEB_MAIN_NO_OTEL_CONTEXT;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule webMvcMainNoInternalPropagationTypes =
            ModuleTaxonomyArchRules.WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule outboundInstrumentationDoesNotCreateSpans =
            noClasses().that().haveSimpleNameStartingWith("PlatformOutbound")
                    .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..")
                    .because("the Java Agent owns outbound HTTP span creation and propagation");
}
