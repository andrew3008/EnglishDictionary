package space.br1440.platform.tracing.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

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
            .should().dependOnClassesThat().resideInAnyPackage("..otel.runtime.versioned..")
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
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.otel.sampling..",
                    "space.br1440.platform.tracing.otel.scrubbing..",
                    "space.br1440.platform.tracing.otel.validation..")
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

    public static final ArchRule SAMPLING_MODEL_IS_PURE = noClasses()
            .that().resideInAPackage("..otel.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..otel.sampling.policy..",
                    "..otel.sampling.engine..",
                    "..otel.sampling.properties..")
            .because("the sampling model must not depend on policy, engine or configuration layers");

    public static final ArchRule PRODUCTION_CHAIN_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.sampling.policy.ProductionSamplingPolicyChain")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "..otel.sampling.policy..",
                    "..otel.sampling.engine..")
            .because("the production policy chain is an internal engine integration type");

    public static final ArchRule VERSIONED_STATE_PRIMITIVE_ONLY_IN_CORE = classes()
            .that().haveSimpleName("VersionedState")
            .or().haveSimpleName("VersionedStateHolder")
            .and().resideOutsideOfPackage("..test..")
            .should().resideInAPackage("..otel.runtime.versioned..")
            .allowEmptyShould(true)
            .because("the versioned-state primitive belongs to the core runtime");

    public static final ArchRule CONTROL_IMPLS_ONLY_IN_CORE = classes()
            .that().implement("space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor")
            .and().areNotAnonymousClasses()
            .and().areNotMemberClasses()
            .and().resideOutsideOfPackage("..test..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().resideInAPackage("..otel.propagation.control..")
            .because("propagation control implementations belong to the core implementation module");

    public static final ArchRule CONTROL_IMPL_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.propagation.control.TrustedDestinationMatchers")
            .should().onlyHaveDependentClassesThat(allowedTrustedDestinationMatchersDependent())
            .allowEmptyShould(true)
            .because("the trusted-destination factory is available only to approved composition owners");

    public static final ArchRule REMOTE_SERVICE_MDC_IMPL_ONLY_IN_CORE = classes()
            .that(new DescribedPredicate<>("be remote-service MDC implementation outside tests") {
                @Override
                public boolean test(JavaClass input) {
                    String simpleName = input.getSimpleName();
                    return ("RemoteServiceMdc".equals(simpleName)
                            || "RemoteServiceTraceContext".equals(simpleName)
                            || "RemoteServiceNameResolver".equals(simpleName))
                            && !input.getPackageName().contains(".test");
                }
            })
            .should().resideInAPackage("..otel.mdc.remote..")
            .allowEmptyShould(true)
            .because("remote-service MDC implementation types belong to the core MDC package");

    public static final ArchRule OTEL_EXTENSION_MDC_FROM_CORE = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.javaagent..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat(apiMdcExceptAllowed())
            .because("the extension must use core MDC services instead of expanding the API MDC surface");

    private ModuleTaxonomyArchRules() {
    }

    private static DescribedPredicate<JavaClass> internalPropagationType() {
        return new DescribedPredicate<>("be internal propagation infrastructure") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return "space.br1440.platform.tracing.otel.propagation.control.TraceControlHeaderInjector"
                        .equals(name)
                        || "space.br1440.platform.tracing.otel.propagation.control.PlatformTraceContextKeys"
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
                return name.startsWith("space.br1440.platform.tracing.otel.")
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
                return !"TracingMdcKeys".equals(simpleName)
                        && !"RemoteServiceNameSource".equals(simpleName);
            }
        };
    }
}
