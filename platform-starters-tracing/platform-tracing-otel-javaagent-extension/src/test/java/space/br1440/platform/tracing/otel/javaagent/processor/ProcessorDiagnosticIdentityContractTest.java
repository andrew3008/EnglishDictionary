package space.br1440.platform.tracing.otel.javaagent.processor;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Modifier;
import java.time.Duration;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;

import space.br1440.platform.tracing.otel.javaagent.processor.composite.PlatformCompositeSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.processor.dropoldest.PlatformDropOldestExportSpanProcessor;

class ProcessorDiagnosticIdentityContractTest {

    private static final String PROCESSOR_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.processor";

    @Test
    void identityContractIsOwnedByExactlySevenDomainProcessorsAndNotAnExternalSpi() {
        // public — доступен из processor.composite (DelegateWiring), но не заявлен как SPI:
        // implementors — закрытый набор из ровно семи доменных процессоров, см. ниже.
        assertThat(Modifier.isPublic(ProcessorDiagnosticIdentity.class.getModifiers())).isTrue();

        List<ProcessorDiagnosticIdentity> processors = List.of(
                new BaggageSpanProcessor(Set.of(), List.of()),
                new EnrichingSpanProcessor(),
                new ScrubbingSpanProcessor(List.of()),
                new ValidatingSpanProcessor(TracingSettingsSource.isolatedValidation(true, false, false)),
                new ClassificationSpanProcessor(Duration.ofSeconds(5), Duration.ofSeconds(1)),
                new SpanWatchdogProcessor(Duration.ofSeconds(5), Duration.ofSeconds(30), System::nanoTime),
                new SpanLimitsMetricsProcessor()
        );

        assertThat(processors)
                .extracting(ProcessorDiagnosticIdentity::diagnosticId)
                .containsExactly(
                        "baggage",
                        "enrichment",
                        "scrubbing",
                        "validation",
                        "classification",
                        "watchdog",
                        "span_limits_metrics");
    }

    @Test
    void infrastructureProcessorsAreNotDiagnosticDelegates() {
        assertThat(ProcessorDiagnosticIdentity.class.isAssignableFrom(PlatformCompositeSpanProcessor.class)).isFalse();
        assertThat(ProcessorDiagnosticIdentity.class.isAssignableFrom(
                PlatformDropOldestExportSpanProcessor.class
        )).isFalse();
    }

    @Test
    void noOtherProductionClassImplementsDiagnosticIdentity() {
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
                        "ScrubbingSpanProcessor",
                        "ValidatingSpanProcessor",
                        "ClassificationSpanProcessor",
                        "SpanWatchdogProcessor",
                        "SpanLimitsMetricsProcessor"
                );
    }
}
