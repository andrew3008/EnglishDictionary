package space.br1440.platform.tracing.otel.javaagent.processor;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import space.br1440.platform.tracing.otel.javaagent.configuration.BaggageKeyPolicy;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSourceTestFixtures;
import space.br1440.platform.tracing.otel.javaagent.control.settings.validation.SpanAttributeValidationLevel;
import space.br1440.platform.tracing.otel.javaagent.identity.AgentInstanceId;
import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.spantimeout.SpanTimeoutProcessor;

class ProcessorDiagnosticIdentityContractTest {

    private static final String PROCESSOR_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.processor..";

    @Test
    void identityContractIsOwnedByExactlyEightDomainProcessorsAndNotAnExternalSpi() {
        // given
        // then
        assertThat(Modifier.isPublic(ProcessorDiagnosticIdentity.class.getModifiers())).isTrue();

        SpanTimeoutProcessor spanTimeout = SpanTimeoutProcessor.start(
                Duration.ofSeconds(5), Duration.ofSeconds(30), Duration.ofDays(365),
                10_000, Integer.MAX_VALUE, Integer.MAX_VALUE
        );

        try {
            List<ProcessorDiagnosticIdentity> processors = List.of(
                    new BaggageSpanProcessor(new BaggageKeyPolicy(Set.of(), List.of())),
                    new EnrichingSpanProcessor(),
                    new RequestIdPropagatingSpanProcessor(),
                    ScrubbingSpanProcessorTestFixtures.fromBuiltInRules(List.of()),
                    new ValidatingSpanProcessor(TracingSettingsSourceTestFixtures.boundValidation(AgentInstanceId.random(), SpanAttributeValidationLevel.WARNING)),
                    new ClassificationSpanProcessor(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                    spanTimeout,
                    new SpanLimitsMetricsProcessor()
            );

            assertThat(processors)
                    .extracting(ProcessorDiagnosticIdentity::diagnosticId)
                    .containsExactly(
                            "baggage",
                            "enrichment",
                            "request_id_propagation",
                            "scrubbing",
                            "validation",
                            "classification",
                            "span_timeout",
                            "span_limits_metrics");
        } finally {
            spanTimeout.shutdown();
        }
    }

    @Test
    void infrastructureProcessorsAreNotDiagnosticDelegates() {
        // given
        Set<String> infrastructureProcessors = Set.of(
                PlatformCompositeSpanProcessor.class.getName(),
                PlatformDropOldestExportSpanProcessor.class.getName()
        );

        List<JavaClass> imported = new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(PROCESSOR_PACKAGE)
                .stream()
                .filter(javaClass -> infrastructureProcessors.contains(javaClass.getName()))
                .toList();

        // then
        assertThat(imported)
                .extracting(JavaClass::getSimpleName)
                .containsExactlyInAnyOrder(
                        "PlatformCompositeSpanProcessor",
                        "PlatformDropOldestExportSpanProcessor");
        assertThat(imported)
                .filteredOn(javaClass -> javaClass.isAssignableTo(ProcessorDiagnosticIdentity.class))
                .isEmpty();
    }

    @Test
    void noOtherProductionClassImplementsDiagnosticIdentity() {
        // given
        // then
        assertThat(new ClassFileImporter()
                .withImportOption(new ImportOption.DoNotIncludeTests())
                .importPackages(PROCESSOR_PACKAGE)
                .stream()
                .filter(javaClass -> javaClass.isAssignableTo(ProcessorDiagnosticIdentity.class))
                .filter(javaClass -> !javaClass.getName().equals(ProcessorDiagnosticIdentity.class.getName()))
                .map(JavaClass::getSimpleName))
                .containsExactlyInAnyOrder(
                        "BaggageSpanProcessor",
                        "EnrichingSpanProcessor",
                        "RequestIdPropagatingSpanProcessor",
                        "ScrubbingSpanProcessor",
                        "ValidatingSpanProcessor",
                        "ClassificationSpanProcessor",
                        "SpanTimeoutProcessor",
                        "SpanLimitsMetricsProcessor"
                );
    }
}
