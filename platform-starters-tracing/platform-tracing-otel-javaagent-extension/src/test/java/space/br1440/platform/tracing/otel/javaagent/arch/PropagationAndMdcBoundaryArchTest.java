package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.ModuleTaxonomyArchRules;

class PropagationAndMdcBoundaryArchTest {

    private static final String PROPAGATION_PACKAGE = "space.br1440.platform.tracing.otel.javaagent.propagation..";

    private static final String PROPAGATION_GATE = "space.br1440.platform.tracing.otel.javaagent.propagation.PlatformPropagationGate";

    private static final JavaClasses EXTENSION_CLASSES = productionClasses("space.br1440.platform.tracing.otel.javaagent");

    @Test
    void propagationDoesNotUseStringBasedTypeDetection() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage(PROPAGATION_PACKAGE)
                .should().callMethodWhere(classGetName())
                .because("propagator types must be detected by type rather than class name strings");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void onlyCustomizerDependsOnConcreteBaggagePropagator() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                .and().doNotHaveSimpleName("BaggagePropagationCustomizer")
                .should().dependOnClassesThat().haveFullyQualifiedName(
                        "io.opentelemetry.api.baggage.propagation.W3CBaggagePropagator")
                .because("only BaggagePropagationCustomizer owns concrete propagator composition");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void propagationDoesNotBypassVisibilityWithReflection() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage(PROPAGATION_PACKAGE)
                .should().callMethodWhere(reflectionBypass())
                .because("propagation code must not bypass narrowed visibility through reflection");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void propagationDecoratorsAreNotPublic() {
        // given
        ArchRule rule = classes()
                .that().haveSimpleName("PlatformBaggagePropagator")
                .or().haveSimpleName("SafeTextMapPropagator")
                .or().haveSimpleName("TraceControlPropagator")
                .should().notBePublic()
                .because("propagation decorators are internal implementation types");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void onlyRuntimeOwnersCallPropagationGate() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
                .and().doNotHaveSimpleName("PlatformPropagationGate")
                .and().doNotHaveSimpleName("BootstrapActivation")
                .and().doNotHaveSimpleName("DiagnosticsObservationSource")
                .and().doNotHaveSimpleName("TraceControlPropagator")
                .should().callMethodWhere(propagationGateAccess())
                .because("only bootstrap, diagnostics and propagator owners may access the propagation gate");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void extensionMdcComesFromCore() {
        // given
        ArchRule rule = ModuleTaxonomyArchRules.OTEL_EXTENSION_MDC_FROM_CORE;

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void agentPropagationDoesNotOwnRequestId() {
        // given
        ArchRule rule = ModuleTaxonomyArchRules.OUTBOUND_DOES_NOT_READ_INBOUND_TRACE_CONTROL_REQUEST_ID;

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void processorCallbacksDoNotDependOnMdc() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage("..javaagent.processor..")
                .should().dependOnClassesThat().haveFullyQualifiedName("org.slf4j.MDC")
                .because("span processor callbacks must not own ThreadLocal MDC state");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void remoteServiceAttributeWrittenOnlyByEnrichingProcessor() {
        // given
        ArchRule rule = ModuleTaxonomyArchRules.REMOTE_SERVICE_ATTRIBUTE_WRITTEN_ONLY_BY_ENRICHING_PROCESSOR;

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void remoteServiceNotConsumedByApplicationPlane() {
        // given
        ArchRule rule = ModuleTaxonomyArchRules.REMOTE_SERVICE_NOT_CONSUMED_BY_APPLICATION_PLANE;

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @SuppressWarnings("SameParameterValue")
    private static JavaClasses productionClasses(String packageName) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packageName);
    }

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
