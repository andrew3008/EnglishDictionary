package space.br1440.platform.tracing.otel.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces that {@code otel.facade} is free of OpenTelemetry dependencies.
 *
 * <p>Motivation: after the {@code fix/tracing-runtime-spi-completeness} refactoring,
 * {@code DefaultTraceOperations} no longer holds OTel-constructors or instanceof-checks.
 * All OTel wiring is done by {@code OtelTracingRuntimeFactory} and autoconfigure.
 * These rules prevent regression.
 *
 * <p>Also guards that {@code otel.facade} does not access {@code otel.runtime.otel}
 * directly, which would re-introduce the same coupling.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class FacadeOtelIsolationArchTest {

    private static final String FACADE_PACKAGE = "space.br1440.platform.tracing.otel.facade..";
    private static final String OTEL_PACKAGE    = "io.opentelemetry..";
    private static final String OTEL_BRIDGE_PKG = "space.br1440.platform.tracing.otel.runtime.otel..";

    /**
     * {@code otel.facade} must not import anything from {@code io.opentelemetry}.
     * OTel wiring belongs in {@code OtelTracingRuntimeFactory} or autoconfigure.
     */
    @ArchTest
    static final ArchRule facadeHasNoOtelImports = noClasses()
            .that().resideInAPackage(FACADE_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(OTEL_PACKAGE)
            .because(
                "otel.facade must not depend on OpenTelemetry API. "
              + "Create OtelTracingRuntime via OtelTracingRuntimeFactory and inject it as TracingRuntime."
            );

    /**
     * {@code otel.facade} must not reference concrete OTel-bridge classes directly.
     * Bridges are allowed to know the facade, not the other way around.
     */
    @ArchTest
    static final ArchRule facadeDoesNotAccessOtelBridge = noClasses()
            .that().resideInAPackage(FACADE_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(OTEL_BRIDGE_PKG)
            .because(
                "otel.facade must not depend on otel.runtime.otel. "
              + "Facade depends only on TracingRuntime SPI. "
              + "OtelTracingRuntime is an implementation detail of the bridge."
            );
}
