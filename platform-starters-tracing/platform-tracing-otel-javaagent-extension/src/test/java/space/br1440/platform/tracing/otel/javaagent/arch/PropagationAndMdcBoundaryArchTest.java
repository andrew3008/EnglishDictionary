package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class PropagationAndMdcBoundaryArchTest {

    private static final String PROPAGATION_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.propagation..";

    private static final String PROPAGATION_GATE =
            "space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate";

    @ArchTest
    static final ArchRule propagationDoesNotUseStringBasedTypeDetection =
            noClasses()
                    .that().resideInAPackage(PROPAGATION_PACKAGE)
                    .should().callMethodWhere(classGetName())
                    .because("propagator types must be detected by type rather than class name strings");

    @ArchTest
    static final ArchRule onlyCustomizerDependsOnConcreteBaggagePropagator =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                    .and().doNotHaveSimpleName("BaggagePropagationCustomizer")
                    .should().dependOnClassesThat().haveFullyQualifiedName(
                            "io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator")
                    .because("only BaggagePropagationCustomizer owns concrete propagator composition");

    @ArchTest
    static final ArchRule propagationDoesNotBypassVisibilityWithReflection =
            noClasses()
                    .that().resideInAPackage(PROPAGATION_PACKAGE)
                    .should().callMethodWhere(reflectionBypass())
                    .because("propagation code must not bypass narrowed visibility through reflection");

    @ArchTest
    static final ArchRule propagationDecoratorsAreNotPublic =
            classes()
                    .that().haveSimpleName("FilteringBaggagePropagator")
                    .or().haveSimpleName("SafeTextMapPropagator")
                    .or().haveSimpleName("FailClosedCorrelationBaggagePropagator")
                    .or().haveSimpleName("TraceControlPropagator")
                    .should().notBePublic()
                    .because("propagation decorators are internal implementation types");

    @ArchTest
    static final ArchRule onlyRuntimeOwnersCallPropagationGate =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                    .and().doNotHaveSimpleName("PlatformPropagationGate")
                    .and().doNotHaveSimpleName("BootstrapCoordinator")
                    .and().doNotHaveSimpleName("DiagnosticsControl")
                    .and().doNotHaveSimpleName("TraceControlPropagator")
                    .should().callMethodWhere(propagationGateAccess())
                    .because("only bootstrap, diagnostics and propagator owners may access the propagation gate");

    @ArchTest
    static final ArchRule extensionMdcComesFromCore =
            ModuleTaxonomyArchRules.OTEL_EXTENSION_MDC_FROM_CORE;

    @ArchTest
    static final ArchRule agentPropagationDoesNotOwnRequestId =
            ModuleTaxonomyArchRules.OUTBOUND_DOES_NOT_READ_INBOUND_TRACE_CONTROL_REQUEST_ID;

    @ArchTest
    static final ArchRule processorCallbacksDoNotDependOnMdc =
            noClasses()
                    .that().resideInAPackage("..javaagent.processor..")
                    .should().dependOnClassesThat().haveFullyQualifiedName("org.slf4j.MDC")
                    .because("span processor callbacks must not own ThreadLocal MDC state");

    private static DescribedPredicate<JavaMethodCall> classGetName() {
        return new DescribedPredicate<>("call java.lang.Class.getName") {
            @Override
            public boolean test(JavaMethodCall call) {
                return "getName".equals(call.getTarget().getName())
                        && "java.lang.Class".equals(call.getTargetOwner().getName());
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> reflectionBypass() {
        return new DescribedPredicate<>("call Class.forName, getDeclaredField or setAccessible") {
            @Override
            public boolean test(JavaMethodCall call) {
                String owner = call.getTargetOwner().getName();
                String name = call.getTarget().getName();
                return "java.lang.Class".equals(owner)
                        && (name.equals("forName") || name.startsWith("getDeclaredField"))
                        || name.equals("setAccessible");
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> propagationGateAccess() {
        return new DescribedPredicate<>("call PlatformPropagationGate runtime accessors") {
            @Override
            public boolean test(JavaMethodCall call) {
                if (!PROPAGATION_GATE.equals(call.getTargetOwner().getName())) {
                    return false;
                }
                String name = call.getTarget().getName();
                return name.equals("shared") || name.equals("bindAcceptedPolicy") || name.equals("isEnabled");
            }
        };
    }
}
