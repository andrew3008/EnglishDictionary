package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.export.SpanExporter;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class ScrubbingBoundaryOwnershipArchTest {

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule boundaryComponentsDoNotUseStandardStreams =
            noClasses()
                    .that().resideInAnyPackage(
                            "..resilience..",
                            "..diagnostics..",
                            "..sampler..",
                            "..propagation..",
                            "..exporter..",
                            "..processor..")
                    .should(ACCESS_STANDARD_STREAMS)
                    .because("tracing failures must use controlled logging instead of standard streams");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule productionExporterIsAssembledOnlyByExportFactory =
            noClasses()
                    .that().doNotHaveSimpleName("PlatformExportProcessorFactory")
                    .and().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructor(
                            SafeSpanExporter.class,
                            SpanExporter.class,
                            TracingSettingsSource.class,
                            ScrubbingRuntime.class)
                    .because("only PlatformExportProcessorFactory may assemble the protected exporter");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule installationBarrierIsAcknowledgedOnlyByPipelineAssembly =
            noClasses()
                    .that().doNotHaveSimpleName("PlatformExportProcessorFactory")
                    .and().doNotHaveSimpleName("PlatformSpanProcessorFactory")
                    .and().doNotHaveSimpleName("ScrubbingRuntime")
                    .should().callMethodWhere(
                            target(owner(type(ScrubbingRuntime.class)))
                                    .and(target(nameStartingWith("acknowledge"))))
                    .because("only pipeline assembly may acknowledge scrubbing installation");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule boundaryMetricsAreRecordedOnlyByBoundaryOwners =
            noClasses()
                    .that().doNotHaveSimpleName("SafeSpanExporter")
                    .and().doNotHaveSimpleName("SpanDataBoundaryScrubber")
                    .and().doNotHaveSimpleName("BoundaryTextPolicy")
                    .and().doNotHaveSimpleName("AttributesScrubber")
                    .and().doNotHaveFullyQualifiedName(
                            "space.br1440.platform.tracing.otel.javaagent.exporter.AttributesScrubber$ScrubPass")
                    .and().doNotHaveSimpleName("ScrubbingRuntime")
                    .should().callMethodWhere(
                            target(owner(type(ScrubbingRuntime.class)))
                                    .and(target(nameStartingWith("recordBoundary"))))
                    .because("only exporter boundary owners may update scrubbing boundary metrics");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule processorsDoNotWriteResourceIdentityAttributes =
            noClasses()
                    .that().resideInAPackage("..processor..")
                    .should().accessField(PlatformAttributes.class, "SERVICE_NAME")
                    .orShould().accessField(PlatformAttributes.class, "SERVICE_VERSION")
                    .orShould().accessField(PlatformAttributes.class, "CONTAINER_ID")
                    .orShould().accessField(PlatformAttributes.class, "PLATFORM_HOST")
                    .orShould().accessField(PlatformAttributes.class, "PLATFORM_ENVIRONMENT")
                    .because("resource identity belongs to Resource rather than span processors");
}
