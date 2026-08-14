package space.br1440.platform.tracing.otel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PA-3: аудит границы {@code otel.runtime.otel} — единственный OTel adapter runtime-слоя.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RuntimeOtelBoundaryArchTest {

    @ArchTest
    static final ArchRule runtimeOtelMustNotDependOnSpan =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.otel.runtime.otel..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.span..")
                    .because("runtime.otel — OTel adapter; span pipeline остаётся выше по слоям");

    @ArchTest
    static final ArchRule runtimeOtelMustNotDependOnFacade =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.otel.runtime.otel..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.facade..")
                    .because("runtime.otel не зависит от facade entrypoints");

    @ArchTest
    static final ArchRule runtimeOtelMustNotDependOnPropagationEntrypoints =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.otel.runtime.otel..")
                    .should().dependOnClassesThat().haveNameMatching(
                            "space\\.br1440\\.platform\\.tracing\\.otel\\.propagation\\."
                                    + "(OtelPlatformContextPropagation|NoOpPlatformContextPropagation)")
                    .because("runtime.otel не зависит от propagation entrypoint-классов");
}
