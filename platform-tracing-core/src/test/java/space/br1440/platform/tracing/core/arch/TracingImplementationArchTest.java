package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaMethod;
import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import space.br1440.platform.tracing.core.runtime.TracingRuntime;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 2 architecture guardrails for {@link TracingRuntime} boundary.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingImplementationArchTest {

    @ArchTest
    static final ArchRule tracingRuntimeHasNoDefaultMethods =
            classes().that().implement(TracingRuntime.class)
                    .should(notHaveDefaultMethods())
                    .because("TracingRuntime SPI must be fully abstract without default methods");

    @ArchTest
    static final ArchRule tracingRuntimeInterfaceHasNoBehavioralStaticHelpers =
            classes().that().areInterfaces().and().haveSimpleName("TracingRuntime")
                    .should(notDeclarePublicStaticMethods())
                    .because("TracingRuntime interface must not expose behavioral static helpers");

    @ArchTest
    static final ArchRule noAbstractTracingRuntimeSkeletons =
            classes().that().implement(TracingRuntime.class)
                    .should(notBeAbstract())
                    .because("abstract partial TracingRuntime skeletons are forbidden");

    @ArchTest
    static final ArchRule runtimeExceptOtelMustNotDependOnOtel =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.runtime..")
                    .and().resideOutsideOfPackage("space.br1440.platform.tracing.core.runtime.otel..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("core.runtime (кроме otel) не должен зависеть от OpenTelemetry API");

    @ArchTest
    static final ArchRule runtimeMustNotDependOnManual =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.runtime..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.core.manual..")
                    .because("core.runtime не должен зависеть от core.manual");

    @ArchTest
    static final ArchRule runtimeMustNotDependOnRootFacade =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.runtime..")
                    .should().dependOnClassesThat().haveNameMatching(
                            "space\\.br1440\\.platform\\.tracing\\.core\\.(DefaultPlatformTracing|NoOpPlatformTracing|OtelPlatformContextPropagation|NoOpPlatformContextPropagation)")
                    .because("core.runtime не должен зависеть от root facade-кlassов");

    @ArchTest
    static final ArchRule manualBuildersDoNotUseOtelDirectly =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.manual..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..")
                    .because("public manual builders must route through TracingRuntime, not OTel API");

    @ArchTest
    static final ArchRule platformTracingFacadeDoesNotUseOtelSpanApi =
            noClasses().that().haveSimpleName("DefaultPlatformTracing")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("PlatformTracing facade must not use OTel span API directly");

    private static ArchCondition<JavaClass> notHaveDefaultMethods() {
        return new ArchCondition<>("not declare default methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isInterface()) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (method.getOwner().isInterface()
                            && !method.getModifiers().contains(JavaModifier.ABSTRACT)
                            && !method.getModifiers().contains(JavaModifier.STATIC)
                            && !method.getModifiers().contains(JavaModifier.PRIVATE)) {
                        events.add(SimpleConditionEvent.violated(method,
                                "Default method forbidden on TracingRuntime: " + method.getFullName()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeclarePublicStaticMethods() {
        return new ArchCondition<>("not declare public static methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (method.getModifiers().contains(JavaModifier.STATIC)
                            && method.getModifiers().contains(JavaModifier.PUBLIC)) {
                        events.add(SimpleConditionEvent.violated(method,
                                "Public static method forbidden on TracingRuntime impl: "
                                        + method.getFullName()));
                    }
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notBeAbstract() {
        return new ArchCondition<>("not be abstract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    events.add(SimpleConditionEvent.violated(item,
                            "Abstract TracingRuntime forbidden: " + item.getFullName()));
                }
            }
        };
    }
}
