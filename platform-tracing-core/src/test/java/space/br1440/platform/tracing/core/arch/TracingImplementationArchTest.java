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
import space.br1440.platform.tracing.core.impl.TracingImplementation;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 2 architecture guardrails for {@link TracingImplementation} boundary.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class TracingImplementationArchTest {

    @ArchTest
    static final ArchRule tracingImplementationHasNoDefaultMethods =
            classes().that().implement(TracingImplementation.class)
                    .should(notHaveDefaultMethods())
                    .because("TracingImplementation SPI must be fully abstract without default methods");

    @ArchTest
    static final ArchRule tracingImplementationInterfaceHasNoBehavioralStaticHelpers =
            classes().that().areInterfaces().and().haveSimpleName("TracingImplementation")
                    .should(notDeclarePublicStaticMethods())
                    .because("TracingImplementation interface must not expose behavioral static helpers");

    @ArchTest
    static final ArchRule noAbstractTracingImplementationSkeletons =
            classes().that().implement(TracingImplementation.class)
                    .should(notBeAbstract())
                    .because("abstract partial TracingImplementation skeletons are forbidden");

    @ArchTest
    static final ArchRule manualBuildersDoNotUseOtelDirectly =
            noClasses().that().resideInAPackage("space.br1440.platform.tracing.core.manual..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..")
                    .because("public manual builders must route through TracingImplementation, not OTel API");

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
                                "Default method forbidden on TracingImplementation: " + method.getFullName()));
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
                                "Public static method forbidden on TracingImplementation impl: "
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
                            "Abstract TracingImplementation forbidden: " + item.getFullName()));
                }
            }
        };
    }
}
