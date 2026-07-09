package space.br1440.platform.tracing.core.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Enforces that {@code core.facade} is free of OpenTelemetry dependencies.
 *
 * <p>Motivation: after the {@code fix/tracing-runtime-spi-completeness} refactoring,
 * {@code DefaultPlatformTracing} no longer holds OTel-constructors or instanceof-checks.
 * All OTel wiring is done by {@code OtelTracingRuntimeFactory} and autoconfigure.
 * These rules prevent regression.
 *
 * <p>Also guards that {@code core.facade} does not access {@code core.runtime.otel}
 * directly, which would re-introduce the same coupling.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.core",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class FacadeOtelIsolationArchTest {

    private static final String FACADE_PACKAGE = "space.br1440.platform.tracing.core.facade..";
    private static final String OTEL_PACKAGE    = "io.opentelemetry..";
    private static final String OTEL_BRIDGE_PKG = "space.br1440.platform.tracing.core.runtime.otel..";

    /**
     * {@code core.facade} must not import anything from {@code io.opentelemetry}.
     * OTel wiring belongs in {@code OtelTracingRuntimeFactory} or autoconfigure.
     */
    @ArchTest
    static final ArchRule facadeHasNoOtelImports = noClasses()
            .that().resideInAPackage(FACADE_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(OTEL_PACKAGE)
            .because(
                "core.facade must not depend on OpenTelemetry API. "
              + "Create OtelTracingRuntime via OtelTracingRuntimeFactory and inject it as TracingRuntime."
            );

    /**
     * {@code core.facade} must not reference concrete OTel-bridge classes directly.
     * Bridges are allowed to know the facade, not the other way around.
     */
    @ArchTest
    static final ArchRule facadeDoesNotAccessOtelBridge = noClasses()
            .that().resideInAPackage(FACADE_PACKAGE)
            .should().dependOnClassesThat().resideInAPackage(OTEL_BRIDGE_PKG)
            .because(
                "core.facade must not depend on core.runtime.otel. "
              + "Facade depends only on TracingRuntime SPI. "
              + "OtelTracingRuntime is an implementation detail of the bridge."
            );
}
