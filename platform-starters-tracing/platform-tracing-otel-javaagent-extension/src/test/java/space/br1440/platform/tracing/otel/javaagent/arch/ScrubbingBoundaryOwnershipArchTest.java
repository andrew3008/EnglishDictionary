package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Collection;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.EvaluationResult;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.exporter.boundary.SpanDataBoundaryScrubber;
import space.br1440.platform.tracing.otel.javaagent.exporter.guard.OtlpRequestSizeLimitingSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.runtime.ScrubbingRuntime;

import io.opentelemetry.sdk.common.CompletableResultCode;
import io.opentelemetry.sdk.trace.data.SpanData;
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
                    .should().callConstructorWhere(target(owner(type(SafeSpanExporter.class))))
                    .because("only PlatformExportProcessorFactory may assemble the protected exporter");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule sizeGuardIsAssembledOnlyByExportFactory =
            noClasses()
                    .that().doNotHaveSimpleName("PlatformExportProcessorFactory")
                    .and().doNotHaveSimpleName("OtlpRequestSizeLimitingSpanExporter")
                    .should().callConstructorWhere(target(owner(type(OtlpRequestSizeLimitingSpanExporter.class))))
                    .because("only PlatformExportProcessorFactory may assemble the request-size guard");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule sizeGuardIsNotASpanExporter =
            noClasses()
                    .that().haveFullyQualifiedName(OtlpRequestSizeLimitingSpanExporter.class.getName())
                    .should().beAssignableTo(SpanExporter.class)
                    .because("the size guard must pass through SafeSpanExporter");

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
                    .and().haveNameNotMatching(".*\\.AttributesScrubber(\\$.*)?")
                    .and().doNotHaveSimpleName("ScrubbingRuntime")
                    .should().callMethodWhere(
                            target(owner(type(ScrubbingRuntime.class)))
                                    .and(target(nameStartingWith("recordBoundary"))))
                    .because("only exporter boundary owners may update scrubbing boundary metrics");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule boundaryScrubberIsConstructedOnlyByTheFacade =
            noClasses()
                    .that().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructorWhere(target(owner(type(SpanDataBoundaryScrubber.class))))
                    .because("the boundary scrubber is assembled only by the export facade");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule outcomeAccumulatorIsConstructedOnlyByTheGuard =
            noClasses()
                    .that().doNotHaveSimpleName("OtlpRequestSizeLimitingSpanExporter")
                    .should().callConstructorWhere(target(owner(name(
                            "space.br1440.platform.tracing.otel.javaagent.exporter.guard.SpanOutcomeAccumulator"))))
                    .because("the outcome accumulator is assembled only by the request-size guard");

    // given/when/then
    @ArchTest
    @SuppressWarnings("unused")
    static final ArchRule outageTrackerIsConstructedOnlyByTheFacade =
            noClasses()
                    .that().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructorWhere(target(owner(name(
                            "space.br1440.platform.tracing.otel.javaagent.exporter.ExportOutageTracker"))))
                    .because("the outage tracker is assembled only by the export facade");

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

    @Test
    void productionExporterRuleDetectsForeignAssembly() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(ForeignSafeExporterAssembly.class);

        // when
        EvaluationResult result = productionExporterIsAssembledOnlyByExportFactory.evaluate(classes);

        // then
        assertThat(classes.get(ForeignSafeExporterAssembly.class)).isNotNull();
        assertThat(result.hasViolation()).isTrue();
    }

    @Test
    void sizeGuardRuleDetectsForeignAssembly() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(ForeignGuardAssembly.class);

        // when
        EvaluationResult result = sizeGuardIsAssembledOnlyByExportFactory.evaluate(classes);

        // then
        assertThat(classes.get(ForeignGuardAssembly.class)).isNotNull();
        assertThat(result.hasViolation()).isTrue();
    }

    @Test
    void spanExporterAssignabilityRuleDetectsAnImplementor() {
        // given
        JavaClasses classes = new ClassFileImporter().importClasses(SpanExporterGuardFixture.class);
        ArchRule rule = noClasses()
                .that().haveFullyQualifiedName(SpanExporterGuardFixture.class.getName())
                .should().beAssignableTo(SpanExporter.class);

        // when
        EvaluationResult result = rule.evaluate(classes);

        // then
        assertThat(classes.get(SpanExporterGuardFixture.class)).isNotNull();
        assertThat(result.hasViolation()).isTrue();
    }

    static final class ForeignSafeExporterAssembly {

        @SuppressWarnings("unused")
        private final SafeSpanExporter exporter = new SafeSpanExporter(null, null, null);
    }

    static final class ForeignGuardAssembly {

        @SuppressWarnings("unused")
        private final OtlpRequestSizeLimitingSpanExporter guard = new OtlpRequestSizeLimitingSpanExporter(null, 1);
    }

    static final class SpanExporterGuardFixture implements SpanExporter {

        @Override
        public CompletableResultCode export(Collection<SpanData> spans) {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode flush() {
            return CompletableResultCode.ofSuccess();
        }

        @Override
        public CompletableResultCode shutdown() {
            return CompletableResultCode.ofSuccess();
        }
    }
}
