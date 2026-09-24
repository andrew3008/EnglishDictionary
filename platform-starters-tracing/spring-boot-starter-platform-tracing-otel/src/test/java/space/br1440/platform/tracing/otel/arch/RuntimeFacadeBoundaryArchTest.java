package space.br1440.platform.tracing.otel.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RuntimeFacadeBoundaryArchTest {

    private static final String OTEL_RUNTIME = "space.br1440.platform.tracing.otel.runtime.otel..";
    private static final String RUNTIME = "space.br1440.platform.tracing.otel.runtime..";
    private static final String FACADE = "space.br1440.platform.tracing.otel.facade..";
    private static final String SPAN_BUILDER = "space.br1440.platform.tracing.otel.span.builder..";

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule facadeDoesNotDependOnOpenTelemetry =
            noClasses().that().resideInAPackage(FACADE)
                    .should().dependOnClassesThat().resideInAPackage("io.opentelemetry..")
                    .because("the public facade must remain isolated from OpenTelemetry implementation types");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule facadeDoesNotDependOnOtelRuntimeAdapter =
            noClasses().that().resideInAPackage(FACADE)
                    .should().dependOnClassesThat().resideInAPackage(OTEL_RUNTIME)
                    .because("the facade must depend on the runtime port, not its OpenTelemetry adapter");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule runtimeDoesNotDependOnSpanOrFacade =
            noClasses().that().resideInAPackage(RUNTIME)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.span..",
                            FACADE)
                    .because("the runtime layer must not depend on higher-level span or facade packages");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule nonOtelRuntimeDoesNotDependOnOpenTelemetry =
            noClasses().that().resideInAPackage(RUNTIME)
                    .and().resideOutsideOfPackage(OTEL_RUNTIME)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.api..",
                            "io.opentelemetry.context..",
                            "io.opentelemetry.sdk..")
                    .because("only the dedicated runtime adapter may depend on OpenTelemetry");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule otelRuntimeDoesNotDependOnApplicationEntrypoints =
            noClasses().that().resideInAPackage(OTEL_RUNTIME)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "space.br1440.platform.tracing.otel.span..",
                            FACADE,
                            "space.br1440.platform.tracing.otel.propagation..")
                    .because("the OpenTelemetry adapter must remain below application entrypoints");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule spanBuildersDoNotDependOnRuntimePolicyOrOpenTelemetry =
            noClasses().that().resideInAPackage(SPAN_BUILDER)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            RUNTIME,
                            "space.br1440.platform.tracing.otel.attributes.validation..",
                            "io.opentelemetry.api.trace..",
                            "io.opentelemetry.context..")
                    .because("span builders must delegate through the platform span factory boundary");

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noLocalCopiesOfOtelSdkClasses =
            OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noFakeOtelPackages =
            OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noLocalOtelContractNames =
            OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES;

    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule noRawRecordExceptionOutsideRecorder =
            OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER;
}
