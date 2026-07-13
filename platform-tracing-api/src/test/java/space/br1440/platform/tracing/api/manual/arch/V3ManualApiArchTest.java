package space.br1440.platform.tracing.api.manual.arch;

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

import java.util.Set;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Slice 1A architecture guardrails for additive v3 manual/spec public API.
 */
@AnalyzeClasses(
        packages = {
                "space.br1440.platform.tracing.api.manual",
                "space.br1440.platform.tracing.api.span.spec"
        },
        importOptions = ImportOption.DoNotIncludeTests.class
)
class V3ManualApiArchTest {

    private static final Set<String> ALLOWED_STATIC_FACTORY_TYPES = Set.of(
            "SpanRelationshipSpec",
            "SpanSpec",
            "SpanSpecAttributeValue"
    );

    private static final Set<String> FORBIDDEN_PUBLIC_NAMES = Set.of(
            "current",
            "currentTraceContext",
            "businessSpan",
            "internalSpan",
            "advanced",
            "escapeHatch",
            "customSpan",
            "rawSpan",
            "raw",
            "justification",
            "execute",
            "manualInstrumentation",
            "instrumented",
            "spans"
    );

    @ArchTest
    static final ArchRule publicFacadesAndBuildersHaveNoDefaultMethods =
            classes()
                    .that().areInterfaces()
                    .should(notHaveDefaultMethods())
                    .because("v3 public facades and builders must not use behavioral default methods");

    @ArchTest
    static final ArchRule noBehavioralStaticHelpersOnFacades =
            classes()
                    .that().areInterfaces()
                    .should(notDeclareBehavioralStaticMethodsExceptAllowedFactories());

    @ArchTest
    static final ArchRule noOpenTelemetryTypesInV3PublicApi =
            noClasses()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("v3 public API must not expose or depend on OpenTelemetry types");

    @ArchTest
    static final ArchRule noAbstractClassesInV3ApiPackages =
            classes()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should(notBeAbstractClass())
                    .because("abstract skeleton implementations are forbidden in v3 API packages");

    @ArchTest
    static final ArchRule forbiddenStalePublicNamesAbsent =
            classes()
                    .that().resideInAnyPackage(
                            "space.br1440.platform.tracing.api.manual..",
                            "space.br1440.platform.tracing.api.span.spec..")
                    .should(notDeclareForbiddenStaleNames());

    private static ArchCondition<JavaClass> notBeAbstractClass() {
        return new ArchCondition<>("not be abstract") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (item.isInterface()) {
                    return;
                }
                if (item.getModifiers().contains(JavaModifier.ABSTRACT)) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "Abstract class " + item.getFullName() + " is forbidden"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notHaveDefaultMethods() {
        return new ArchCondition<>("not declare default methods") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (!item.isInterface()) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (isDefaultInterfaceMethod(method)) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                "Default method " + method.getFullName() + " is forbidden"));
                    }
                }
            }
        };
    }

    private static boolean isDefaultInterfaceMethod(JavaMethod method) {
        return method.getOwner().isInterface()
                && !method.getModifiers().contains(JavaModifier.ABSTRACT)
                && !method.getModifiers().contains(JavaModifier.STATIC)
                && !method.getModifiers().contains(JavaModifier.PRIVATE);
    }

    private static ArchCondition<JavaClass> notDeclareBehavioralStaticMethodsExceptAllowedFactories() {
        return new ArchCondition<>("not declare behavioral static helpers except value/spec factories") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                if (ALLOWED_STATIC_FACTORY_TYPES.contains(item.getSimpleName())) {
                    return;
                }
                for (JavaMethod method : item.getMethods()) {
                    if (!method.getModifiers().contains(JavaModifier.STATIC)) {
                        continue;
                    }
                    if (method.getModifiers().contains(JavaModifier.PRIVATE)
                            || method.getModifiers().contains(JavaModifier.PROTECTED)) {
                        continue;
                    }
                    events.add(SimpleConditionEvent.violated(
                            method,
                            "Static method " + method.getFullName()
                                    + " is forbidden on public facade/builder types"));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> notDeclareForbiddenStaleNames() {
        return new ArchCondition<>("not declare forbidden stale public names") {
            @Override
            public void check(JavaClass item, ConditionEvents events) {
                for (JavaMethod method : item.getMethods()) {
                    if (FORBIDDEN_PUBLIC_NAMES.contains(method.getName())) {
                        events.add(SimpleConditionEvent.violated(
                                method,
                                "Forbidden stale public name: " + method.getName()));
                    }
                }
                if (FORBIDDEN_PUBLIC_NAMES.contains(item.getSimpleName())
                        || "CurrentTraceContext".equals(item.getSimpleName())
                        || "AdvancedTracing".equals(item.getSimpleName())
                        || "ManualInstrumentation".equals(item.getSimpleName())
                        || "InstrumentedTracing".equals(item.getSimpleName())) {
                    events.add(SimpleConditionEvent.violated(
                            item,
                            "Forbidden stale public type name: " + item.getSimpleName()));
                }
            }
        };
    }
}
