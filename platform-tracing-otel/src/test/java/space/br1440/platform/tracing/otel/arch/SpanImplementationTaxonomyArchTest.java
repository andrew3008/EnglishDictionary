package space.br1440.platform.tracing.otel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PA-1: изоляция {@code otel.span.builder} от runtime/policy деталей.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class SpanImplementationTaxonomyArchTest {

    @ArchTest
    static final ArchRule spanBuilderMustNotDependOnTracingRuntime =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.otel.span.builder..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.runtime..")
                    .because("otel.span.builder не должен зависеть от otel.runtime");

    @ArchTest
    static final ArchRule spanBuilderMustNotDependOnAttributePolicy =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.otel.span.builder..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.semconv.policy..")
                    .because("otel.span.builder не должен зависеть от otel.semconv.policy");
}
