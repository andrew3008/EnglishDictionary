package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules;

/**
 * ArchUnit guardrails direct OTel integration для {@code platform-tracing-otel-javaagent-extension}.
 * <p>
 * Import scope ограничен {@code space.br1440.platform.tracing.otel} без test-классов —
 * см. Javadoc {@link OtelDirectIntegrationRules}.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class OtelDirectIntegrationArchTest {

    @ArchTest
    static final ArchRule noLocalCopiesOfOtelSdkClasses =
            OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES;

    @ArchTest
    static final ArchRule noFakeOtelPackages =
            OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES;

    @ArchTest
    static final ArchRule noLocalOtelContractNames =
            OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES;

    @ArchTest
    static final ArchRule noRawRecordExceptionOutsideRecorder =
            OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER;

    @ArchTest
    static final ArchRule baggageSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.BAGGAGE_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule enrichingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.ENRICHING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule scrubbingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.SCRUBBING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule validatingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.VALIDATING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule spanWatchdogProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.SPAN_WATCHDOG_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule platformCompositeSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_COMPOSITE_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule compositeSamplerImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.COMPOSITE_SAMPLER_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule platformResourceProviderImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_RESOURCE_PROVIDER_IMPLEMENTS_SPI;

    @ArchTest
    static final ArchRule platformAutoConfigurationCustomizerImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_AUTO_CONFIGURATION_CUSTOMIZER_IMPLEMENTS_SPI;
}
