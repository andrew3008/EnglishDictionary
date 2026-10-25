package space.br1440.platform.tracing.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.api.propagation.control.InboundTraceControl;

public final class ModuleTaxonomyArchRules {

    public static final ArchRule API_MAIN_NO_IMPLEMENTATION_DEPENDENCY = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.otel..",
                    "space.br1440.platform.tracing.autoconfigure..")
            .because("the published API contains only stable contracts and value types");

    public static final ArchRule API_MAIN_NO_OTEL_OR_FRAMEWORK_TYPES = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry..",
                    "org.springframework..",
                    "reactor..",
                    "jakarta.servlet..",
                    "org.apache.kafka..",
                    "io.grpc..",
                    "org.slf4j..")
            .because("the published API must remain OpenTelemetry-free and framework-free");

    public static final ArchRule API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL = noClasses()
            .that().resideInAPackage("..api.propagation.control..")
            .and().areNotInterfaces()
            .and().areNotRecords()
            .and().areNotEnums()
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("the propagation control API contains only interfaces, records and enums");

    public static final ArchRule IDENTITY_INTERNAL_TYPES_NOT_PUBLIC = noClasses()
            .that().arePublic()
            .should().haveNameMatching(
                    ".*\\.(RequestContextAccessor|RequestIdentityAccessor|RequestIdentityContext|"
                            + "RequestIdentityBinder|RequestIdentityScope)")
            .because("identity storage and binding infrastructure must remain internal");

    public static final ArchRule AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.otel.javaagent..")
            .because("the application classloader must not depend on Java Agent implementation classes");

    public static final ArchRule APP_MODULES_NOT_DEPEND_ON_CORE_RUNTIME_VERSIONED = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.starter..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("..runtime.versioned..")
            .because("versioned runtime state is agent infrastructure, not an application SDK");

    public static final ArchRule WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.otel..")
            .because("web autoconfiguration must not expose the core implementation on consumer classpaths");

    public static final ArchRule WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.otel..")
            .because("web integration must use the public API and approved autoconfiguration boundary");

    public static final ArchRule WEB_MAIN_NO_OTEL_CONTEXT = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry.context..")
            .because("OpenTelemetry Context is owned by the implementation and composition layers");

    public static final ArchRule WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat(internalPropagationType())
            .because("web integration must use the public outbound propagation port");

    public static final ArchRule WEBMVC_MAIN_NO_WEBFLUX_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.servlet..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.reactive..",
                    "org.springframework.http.server.reactive..",
                    "reactor.core..")
            .because("the WebMVC integration must not depend on WebFlux or Reactor");

    public static final ArchRule WEBFLUX_MAIN_NO_SERVLET_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.servlet..",
                    "org.springframework.web.servlet..")
            .because("the WebFlux integration must not depend on Servlet or Spring MVC");

    public static final ArchRule CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING = noClasses()
            .that().resideInAnyPackage("space.br1440.platform.tracing.runtime.sampling..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry..",
                    "org.springframework..",
                    "javax.management..")
            .because("core policy packages must remain independent from OpenTelemetry, Spring and JMX");

    public static final ArchRule CORE_MAIN_NO_JMX = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("javax.management..")
            .because("runtime management belongs to the agent-side control plane");

    public static final ArchRule RUNTIME_POLICY_LOGGING_AGNOSTIC = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.runtime..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.slf4j..",
                    "java.util.logging..",
                    "org.springframework..",
                    "org.springframework.boot..",
                    "ch.qos.logback..",
                    "net.logstash.logback..",
                    "reactor..",
                    "jakarta.servlet..",
                    "io.micrometer..",
                    "javax.management..",
                    "space.br1440.platform.logging..",
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.otel.javaagent..")
            .because("runtime-policy is a logging-agnostic shared primitive and must not depend on Agent or Spring");

    public static final ArchRule OTEL_DOES_NOT_DEPEND_ON_AGENT_NAMED_MODULE = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel..")
            .and().resideOutsideOfPackage("..javaagent..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.otel.javaagent..")
            .because("the application plane must not depend on an Agent-named module");

    public static final ArchRule SAMPLING_MODEL_IS_PURE = noClasses()
            .that().resideInAPackage("..runtime.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..runtime.sampling.policy..",
                    "..runtime.sampling.engine..",
                    "..runtime.sampling.properties..")
            .because("the sampling model must not depend on policy, engine or configuration layers");

    public static final ArchRule PRODUCTION_CHAIN_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.runtime.sampling.policy.ProductionSamplingPolicyChain")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "..runtime.sampling.policy..",
                    "..runtime.sampling.engine..")
            .because("the production policy chain is an internal engine integration type");

    public static final ArchRule CONTROL_IMPLS_ONLY_IN_RUNTIME_POLICY = classes()
            .that().implement("space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor")
            .and().areNotAnonymousClasses()
            .and().areNotMemberClasses()
            .and().resideOutsideOfPackage("..test..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().resideInAPackage("..runtime.propagation.control..")
            .because("propagation control implementations belong to the shared runtime-policy module");

    public static final ArchRule CONTROL_IMPL_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.runtime.propagation.control.TrustedDestinationMatchers")
            .should().onlyHaveDependentClassesThat(allowedTrustedDestinationMatchersDependent())
            .allowEmptyShould(true)
            .because("the trusted-destination factory is available only to approved composition owners");

    public static final ArchRule OUTBOUND_DOES_NOT_READ_INBOUND_TRACE_CONTROL_REQUEST_ID = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.otel.javaagent.propagation..",
                    "space.br1440.platform.tracing.runtime.propagation.control..")
            .should().callMethod(InboundTraceControl.class, "requestId")
            .because("requestId for outbound/application-identity semantics is owned by application"
                    + " identity, not the agent-plane InboundTraceControl");

    public static final ArchRule OTEL_EXTENSION_MDC_FROM_CORE = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat(apiMdcExceptAllowed())
            .because("the extension must use core MDC services instead of expanding the API MDC surface");

    public static final ArchRule REMOTE_SERVICE_ATTRIBUTE_WRITTEN_ONLY_BY_ENRICHING_PROCESSOR = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
            .and().doNotHaveSimpleName("EnrichingSpanProcessor")
            .should().accessField(PlatformAttributes.class, "PLATFORM_REMOTE_SERVICE")
            .allowEmptyShould(true)
            .because("platform.remote.service is ERROR CLIENT telemetry written only by EnrichingSpanProcessor");

    public static final ArchRule REMOTE_SERVICE_NOT_CONSUMED_BY_APPLICATION_PLANE = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.api.mdc..",
                    "space.br1440.platform.tracing.otel.mdc..",
                    "space.br1440.platform.tracing.otel.context..",
                    "space.br1440.platform.tracing.otel.facade..",
                    "space.br1440.platform.tracing.otel.runtime..",
                    "space.br1440.platform.tracing.autoconfigure..")
            .should().accessField(PlatformAttributes.class, "PLATFORM_REMOTE_SERVICE")
            .allowEmptyShould(true)
            .because("platform.remote.service is agent telemetry, not MDC, snapshot or error-domain input");

    private ModuleTaxonomyArchRules() {
    }

    private static DescribedPredicate<JavaClass> internalPropagationType() {
        return new DescribedPredicate<>("be internal propagation infrastructure") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return "space.br1440.platform.tracing.runtime.propagation.control.PlatformTraceContextKeys"
                        .equals(name)
                        || "space.br1440.platform.tracing.otel.propagation.OtelTraceparentReader"
                        .equals(name);
            }
        };
    }

    private static DescribedPredicate<JavaClass> allowedTrustedDestinationMatchersDependent() {
        return new DescribedPredicate<>("be an approved trusted-destination factory consumer") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.runtime.")
                        || name.startsWith("space.br1440.platform.tracing.otel.")
                        || name.startsWith("space.br1440.platform.tracing.autoconfigure.")
                        || name.startsWith("space.br1440.platform.tracing.otel.javaagent.")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }

    private static DescribedPredicate<JavaClass> apiMdcExceptAllowed() {
        return new DescribedPredicate<>("be an unapproved API MDC type") {
            @Override
            public boolean test(JavaClass input) {
                if (!input.getPackageName().contains(".api.mdc")) {
                    return false;
                }

                String simpleName = input.getSimpleName();
                return !"TracingMdcKeys".equals(simpleName);
            }
        };
    }
}
