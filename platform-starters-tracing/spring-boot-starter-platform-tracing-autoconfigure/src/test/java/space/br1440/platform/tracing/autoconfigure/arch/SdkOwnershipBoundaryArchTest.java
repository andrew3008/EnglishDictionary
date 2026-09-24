package space.br1440.platform.tracing.autoconfigure.arch;

import java.util.Set;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaMethodCall;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Agent-package-refactor Slice 4 boundary: {@code platform-tracing-spring-boot-autoconfigure} wires
 * beans around the OpenTelemetry SDK, it never constructs, registers, or resets it — the Controlled
 * Platform Agent owns SDK lifecycle end to end (see
 * docs/analysis/tracing-autoconfigure-agent-package-refactoring-plan.md, Slice 4). The only permitted
 * read access to the agent-installed global SDK instance ({@code GlobalOpenTelemetry.get()} /
 * {@code isSet()}) is the single composition root,
 * {@link space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.autoconfigure",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class SdkOwnershipBoundaryArchTest {

    private static final String COMPOSITION_ROOT =
            "space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration";

    private static final String GLOBAL_OPEN_TELEMETRY_FQN = "io.opentelemetry.api.GlobalOpenTelemetry";

    private static final Set<String> SDK_CONSTRUCTION_OWNER_FQNS = Set.of(
            "io.opentelemetry.sdk.OpenTelemetrySdk",
            "io.opentelemetry.sdk.OpenTelemetrySdkBuilder",
            "io.opentelemetry.sdk.trace.SdkTracerProvider",
            "io.opentelemetry.sdk.trace.SdkTracerProviderBuilder",
            "io.opentelemetry.sdk.trace.export.BatchSpanProcessor",
            "io.opentelemetry.sdk.trace.export.SimpleSpanProcessor",
            "io.opentelemetry.exporter.otlp.trace.OtlpHttpSpanExporter",
            "io.opentelemetry.exporter.otlp.trace.OtlpGrpcSpanExporter");

    private static final Set<String> GLOBAL_MUTATION_METHOD_NAMES = Set.of(
            "set", "resetForTest", "buildAndRegisterGlobal");

    private static final Set<String> GLOBAL_READ_METHOD_NAMES = Set.of("get", "isSet");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noOtelSdkConstruction = noClasses()
            .should().callMethodWhere(callTargetOwnerIn(SDK_CONSTRUCTION_OWNER_FQNS))
            .because("Spring auto-configuration must not construct or configure the OpenTelemetry SDK "
                    + "directly (SdkTracerProvider/exporters/processors); the Controlled Platform Agent "
                    + "owns SDK construction");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noGlobalOpenTelemetryMutation = noClasses()
            .should().callMethodWhere(isGlobalOpenTelemetryMethod(GLOBAL_MUTATION_METHOD_NAMES))
            .because("GlobalOpenTelemetry.set/resetForTest/buildAndRegisterGlobal would perform a "
                    + "second SDK registration; only the Controlled Platform Agent registers the "
                    + "global SDK instance");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule globalOpenTelemetryReadConfinedToCompositionRoot = noClasses()
            .that().doNotHaveFullyQualifiedName(COMPOSITION_ROOT)
            .should().callMethodWhere(isGlobalOpenTelemetryMethod(GLOBAL_READ_METHOD_NAMES))
            .because("GlobalOpenTelemetry.get()/isSet() reads the agent-installed SDK instance and is "
                    + "confined to the single composition root, TracingRuntimeAutoConfiguration");

    @SuppressWarnings("SameParameterValue")
    private static DescribedPredicate<JavaMethodCall> callTargetOwnerIn(Set<String> ownerFqns) {
        return new DescribedPredicate<>("target owner in " + ownerFqns) {

            @Override
            public boolean test(JavaMethodCall call) {
                return ownerFqns.contains(call.getTargetOwner().getName());
            }
        };
    }

    private static DescribedPredicate<JavaMethodCall> isGlobalOpenTelemetryMethod(Set<String> methodNames) {
        return new DescribedPredicate<>("call " + GLOBAL_OPEN_TELEMETRY_FQN + "." + methodNames + "(..)") {

            @Override
            public boolean test(JavaMethodCall call) {
                return GLOBAL_OPEN_TELEMETRY_FQN.equals(call.getTargetOwner().getName())
                        && methodNames.contains(call.getTarget().getName());
            }
        };
    }
}
