package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.base.DescribedPredicate.not;
import static com.tngtech.archunit.core.domain.JavaAccess.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.equivalentTo;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAnyPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.name;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.constructors;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.CompositeArchRule;

import io.opentelemetry.javaagent.extension.AgentListener;
import io.opentelemetry.javaagent.tooling.LoggingCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;
import io.opentelemetry.sdk.trace.SdkTracerProviderBuilder;
import io.opentelemetry.sdk.trace.SpanProcessor;
import io.opentelemetry.sdk.trace.internal.ExtendedSpanProcessor;
import io.opentelemetry.sdk.trace.samplers.Sampler;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class OtelDirectIntegrationExtensionSpiRules {

    private static final String STARTUP_LOGGING_ADAPTER =
            "space.br1440.platform.tracing.otel.javaagent.bootstrap.PlatformStartupLoggingCustomizer";

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

    public static final ArchRule PRODUCTION_DOES_NOT_OVERRIDE_SDK_SPAN_LIMITS = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
            .should().accessTargetWhere(target(owner(type(SdkTracerProviderBuilder.class)))
                    .and(target(name("setSpanLimits"))))
            .because("the pinned SDK autoconfiguration owns immutable span limits; "
                    + "the extension binds the actual limit after SDK creation");

    public static final ArchRule BOOTSTRAP_DOES_NOT_DEPEND_ON_AGENT_TOOLING = CompositeArchRule.of(noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent.bootstrap..")
                    .and().doNotHaveFullyQualifiedName(STARTUP_LOGGING_ADAPTER)
                    .should().dependOnClassesThat().resideInAnyPackage(
                            "io.opentelemetry.javaagent.tooling..",
                            "io.opentelemetry.javaagent.bootstrap.."))
            .and(noClasses()
                    .that().haveFullyQualifiedName(STARTUP_LOGGING_ADAPTER)
                    .should().dependOnClassesThat(resideInAnyPackage(
                                    "io.opentelemetry.javaagent.tooling..",
                                    "io.opentelemetry.javaagent.bootstrap..")
                            .and(not(equivalentTo(LoggingCustomizer.class)))
                    )
            )
            .because("only the startup logging adapter may depend on the pinned LoggingCustomizer contract; "
                    + "all other agent tooling and bootstrap implementation dependencies remain forbidden");
}
