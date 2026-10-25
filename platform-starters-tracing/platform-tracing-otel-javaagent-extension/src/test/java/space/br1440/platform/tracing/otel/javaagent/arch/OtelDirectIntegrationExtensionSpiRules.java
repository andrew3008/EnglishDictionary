package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtelDirectIntegrationExtensionSpiRules {

    public static final ArchRule BAGGAGE_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("BaggageSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule ENRICHING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("EnrichingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule ENRICHING_SPAN_PROCESSOR_HAS_SINGLE_NO_ARG_CONSTRUCTOR = constructors()
            .that().areDeclaredInClassesThat().haveSimpleName("EnrichingSpanProcessor")
            .and().areDeclaredInClassesThat().resideInAPackage("space.br1440.platform.tracing..")
            .should().haveRawParameterTypes(new Class<?>[0])
            .because("remote-service priority is an ADR constant, not a constructor SPI");

    public static final ArchRule SCRUBBING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("ScrubbingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule VALIDATING_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("ValidatingSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule SPAN_TIMEOUT_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("SpanTimeoutProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule SPAN_TIMEOUT_PROCESSOR_DOES_NOT_USE_INTERNAL_SPI = classes()
            .that().haveSimpleName("SpanTimeoutProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().notBeAssignableTo(ExtendedSpanProcessor.class);

    public static final ArchRule PLATFORM_COMPOSITE_SPAN_PROCESSOR_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("PlatformCompositeSpanProcessor")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(SpanProcessor.class);

    public static final ArchRule COMPOSITE_SAMPLER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("CompositeSampler")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(Sampler.class);

    public static final ArchRule PLATFORM_AUTO_CONFIGURATION_CUSTOMIZER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("BootstrapAutoConfigurationCustomizerProvider")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(AutoConfigurationCustomizerProvider.class);

    public static final ArchRule PLATFORM_ACTIVATION_AGENT_LISTENER_IMPLEMENTS_SPI = classes()
            .that().haveSimpleName("ActivationAgentListener")
            .and().resideInAPackage("space.br1440.platform.tracing..")
            .should().beAssignableTo(AgentListener.class);

    public static final ArchRule PRODUCTION_DOES_NOT_DEPEND_ON_LEGACY_TRACER_PROVIDER_CONFIGURER = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
            .should().dependOnClassesThat()
            .haveFullyQualifiedName(
                    "io.opentelemetry.sdk.autoconfigure.spi.traces.SdkTracerProviderConfigurer")
            .because("SdkTracerProviderConfigurer is forbidden in the secure Controlled Agent profile; "
                    + "it is applied after exporter/processor customization and can add a parallel "
                    + "unwrapped export path");

    public static final ArchRule BOOTSTRAP_DOES_NOT_DEPEND_ON_AGENT_TOOLING = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent.bootstrap..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.javaagent.tooling..",
                    "io.opentelemetry.javaagent.bootstrap..")
            .because("platform bootstrap uses only the public AgentListener extension API; "
                    + "agent tooling / bootstrap-implementation packages are forbidden (S6)");
}
