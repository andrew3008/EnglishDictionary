package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;
import space.br1440.platform.tracing.test.arch.OtelDirectIntegrationRules;

@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class OtelDirectIntegrationArchTest {

    // given/when/then
    @ArchTest
    static final ArchRule noLocalCopiesOfOtelSdkClasses =
            OtelDirectIntegrationRules.NO_LOCAL_COPIES_OF_OTEL_SDK_CLASSES;

    // given/when/then
    @ArchTest
    static final ArchRule noFakeOtelPackages =
            OtelDirectIntegrationRules.NO_FAKE_OTEL_PACKAGES;

    // given/when/then
    @ArchTest
    static final ArchRule noLocalOtelContractNames =
            OtelDirectIntegrationRules.NO_LOCAL_OTEL_CONTRACT_NAMES;

    // given/when/then
    @ArchTest
    static final ArchRule noRawRecordExceptionOutsideRecorder =
            OtelDirectIntegrationRules.NO_RAW_RECORD_EXCEPTION_OUTSIDE_RECORDER;

    // given/when/then
    @ArchTest
    static final ArchRule baggageSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.BAGGAGE_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule enrichingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.ENRICHING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule enrichingSpanProcessorHasSingleNoArgConstructor =
            OtelDirectIntegrationExtensionSpiRules.ENRICHING_SPAN_PROCESSOR_HAS_SINGLE_NO_ARG_CONSTRUCTOR;

    // given/when/then
    @ArchTest
    static final ArchRule scrubbingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.SCRUBBING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule validatingSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.VALIDATING_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule spanTimeoutProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.SPAN_TIMEOUT_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule spanTimeoutProcessorDoesNotUseInternalSpi =
            OtelDirectIntegrationExtensionSpiRules.SPAN_TIMEOUT_PROCESSOR_DOES_NOT_USE_INTERNAL_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule platformCompositeSpanProcessorImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_COMPOSITE_SPAN_PROCESSOR_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule compositeSamplerImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.COMPOSITE_SAMPLER_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule platformAutoConfigurationCustomizerImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_AUTO_CONFIGURATION_CUSTOMIZER_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule platformActivationAgentListenerImplementsSpi =
            OtelDirectIntegrationExtensionSpiRules.PLATFORM_ACTIVATION_AGENT_LISTENER_IMPLEMENTS_SPI;

    // given/when/then
    @ArchTest
    static final ArchRule productionDoesNotDependOnLegacyTracerProviderConfigurer =
            OtelDirectIntegrationExtensionSpiRules.PRODUCTION_DOES_NOT_DEPEND_ON_LEGACY_TRACER_PROVIDER_CONFIGURER;

    // given/when/then
    @ArchTest
    static final ArchRule bootstrapDoesNotDependOnAgentTooling =
            OtelDirectIntegrationExtensionSpiRules.BOOTSTRAP_DOES_NOT_DEPEND_ON_AGENT_TOOLING;
}
