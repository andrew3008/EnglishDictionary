package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import lombok.experimental.UtilityClass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-1 module taxonomy dependency guardrails (preservation-first migration).
 * <p>
 * These rules enforce <b>forward</b> dependency boundaries. They do not fail on
 * pre-existing known violations outside their scope (e.g. {@code platform-tracing-core}
 * {@code api opentelemetry-api} — documented as MIGRATION_RISK in
 * {@code docs/architecture/platform-tracing-module-taxonomy.md}).
 *
 * @see docs/architecture/platform-tracing-module-taxonomy.md
 */
@UtilityClass
public final class ModuleTaxonomyArchRules {

    /**
     * Traceparent parsing is delegated to the OTel-backed bridge; do not reintroduce public parser APIs.
     */
    public static final ArchRule API_PROPAGATION_HAS_NO_PUBLIC_PARSERS = noClasses()
            .that().resideInAPackage("..api.propagation..")
            .and().arePublic()
            .should().haveSimpleNameEndingWith("Parser")
            .allowEmptyShould(true)
            .because("raw propagation wire parsing is delegated to OTel-backed bridges, not public *Parser APIs");

    /**
     * {@code OtelTraceparentReaderImpl} lives in core and is not extension API.
     * Dependent classes must reside in allowed core/test packages.
     * Package-pattern based to avoid brittle hardcoded FQN lists.
     */
    public static final ArchRule OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.OtelTraceparentReaderImpl")
            .should().onlyHaveDependentClassesThat(allowedOtelTraceparentReaderDependent())
            .allowEmptyShould(true)
            .because("OtelTraceparentReaderImpl is an internal bridge in core, not an extension API");

    /**
     * Application classpath autoconfigure must not depend on agent extension implementation.
     * <p>
     * Agent extension loads in ExtensionClassLoader; App CL must not import
     * {@code platform-tracing-otel-extension} production classes directly.
     */
    public static final ArchRule AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.otel.extension..")
            .allowEmptyShould(true)
            .because("platform-tracing-spring-boot-autoconfigure (App CL) must not depend on "
                    + "platform-tracing-otel-extension (Agent CL) implementation classes");

    /**
     * Servlet web autoconfigure must not pull WebFlux/Reactor stack types into main sources.
     */
    public static final ArchRule WEBMVC_MAIN_NO_WEBFLUX_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.servlet..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.reactive..",
                    "org.springframework.http.server.reactive..",
                    "reactor.core..")
            .allowEmptyShould(true)
            .because("platform-tracing-autoconfigure-webmvc must not depend on WebFlux/Reactor types");

    /**
     * Reactive web autoconfigure must not pull Servlet/MVC stack types into main sources.
     */
    public static final ArchRule WEBFLUX_MAIN_NO_SERVLET_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.servlet..",
                    "org.springframework.web.servlet..")
            .allowEmptyShould(true)
            .because("platform-tracing-autoconfigure-webflux must not depend on Servlet/MVC types");

    /**
     * Future pure policy packages in core (post PR-6 extraction targets).
     */
    public static final ArchRule CORE_POLICY_PACKAGES_NO_OTEL_OR_SPRING = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.core.sampling..",
                    "space.br1440.platform.tracing.core.scrubbing..",
                    "space.br1440.platform.tracing.core.validation..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry..",
                    "org.springframework..",
                    "javax.management..")
            .allowEmptyShould(true)
            .because("pure policy packages under core.{sampling,scrubbing,validation,enrichment} "
                    + "must not depend on OTel, Spring, or JMX (PR-9B boundary hardening)");

    /**
     * PR-9B: entire core module main sources must not use JMX.
     */
    public static final ArchRule CORE_MAIN_NO_JMX = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.core..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("javax.management..")
            .because("platform-tracing-core must not depend on JMX — runtime control is agent-side only");

    // -- Слоистые границы пакета core.sampling: model / policy / engine / config -------------------

    public static final ArchRule SAMPLING_MODEL_IS_PURE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.model — чистый слой состояния, без зависимостей на policy/engine/properties");

    public static final ArchRule SAMPLING_PROPERTIES_DEPENDS_ONLY_ON_MODEL = noClasses()
            .that().resideInAPackage("..core.sampling.properties..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("core.sampling.properties — compile-time нормализация/валидация; зависит только от model");

    public static final ArchRule SAMPLING_POLICY_NO_ENGINE_OR_CONFIG = noClasses()
            .that().resideInAPackage("..core.sampling.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.policy — семантический слой правил; зависит только от model");

    public static final ArchRule SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .because("core.sampling.model — чистый domain compile state; не зависит от api.runtime.state");

    public static final ArchRule VERSIONED_STATE_IMPLS_ALLOWLIST = classes()
            .that().implement("space.br1440.platform.tracing.api.runtime.state.VersionedState")
            .and().resideOutsideOfPackage("..test..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.extension.sampler.SamplerState")
            .orShould().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot")
            .orShould().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.validation.ValidationSnapshot")
            .because("VersionedState — контракт holder-managed runtime state, не generic HasVersion marker");

    public static final ArchRule APP_MODULES_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.starter..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .allowEmptyShould(true)
            .because("api.runtime.state — agent/runtime infrastructure, не SDK для autoconfigure");

    public static final ArchRule NO_API_CONFIG_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.config..")
            .because("api.config удалён; используйте api.runtime.state.VersionedState/VersionedStateHolder");

    public static final ArchRule NO_LEGACY_SPAN_BUILDER_API = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.span.builder..")
            .because("api.span.builder.* удалён в рефакторинге Fable_5 v1.2; используйте api.manual.*");

    public static final ArchRule SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("SamplerState")
            .should().beFinal()
            .because("VersionedState snapshots must remain immutable (SamplerState)");

    public static final ArchRule SCRUBBING_SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("ScrubbingSnapshot")
            .should().beFinal()
            .because("VersionedState snapshots must remain immutable (ScrubbingSnapshot)");

    public static final ArchRule VALIDATION_SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("ValidationSnapshot")
            .should().beFinal()
            .because("VersionedState snapshots must remain immutable (ValidationSnapshot)");

    public static final ArchRule SAMPLING_RULE_IMPLS_ONLY_IN_POLICY = classes()
            .that().implement("space.br1440.platform.tracing.core.sampling.policy.SamplingPolicyRule")
            .should().resideInAPackage("..core.sampling.policy..")
            .allowEmptyShould(true)
            .because("реализации SamplingPolicyRule — не extension API; допустимы только в core.sampling.policy");

    public static final ArchRule PRODUCTION_CHAIN_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.sampling.policy.ProductionSamplingPolicyChain")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("ProductionSamplingPolicyChain public только ради cross-package компиляции движка; "
                    + "не является публичным extension API");

    public static final ArchRule CORE_ENRICHMENT_NO_MANUAL_OR_LEGACY = noClasses()
            .that().resideInAPackage("..core.enrichment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.span.legacy..",
                    "..core.manual..")
            .allowEmptyShould(true)
            .because("core.enrichment — agent-first обогащение; не зависит от manual/legacy builders");

    public static final ArchRule CORE_NAMING_NO_OTEL_TRACE_CONTEXT = noClasses()
            .that().resideInAPackage("..core.naming..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.api.trace..",
                    "io.opentelemetry.context..")
            .allowEmptyShould(true)
            .because("core.naming не импортирует OTel trace/context API");

    public static final ArchRule CORE_SEMCONV_POLICY_OTEL_COMMON_ONLY = noClasses()
            .that().resideInAPackage("..core.semconv.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.api.trace..",
                    "io.opentelemetry.context..",
                    "io.opentelemetry.sdk..")
            .allowEmptyShould(true)
            .because("core.semconv.policy допускает только io.opentelemetry.api.common");

    // -------------------------------------------------------------------------
    // Private helpers
    // -------------------------------------------------------------------------

    /**
     * Package-pattern based predicate — avoids brittle hardcoded FQNs.
     * Allowed dependents:
     * - core.manual.* (AbstractSemanticSpanBuilder)
     * - core.propagation.* (OtelTraceparentReaderImpl itself)
     * - any test class (by package or name convention)
     * NOTE: samples are intentionally excluded — sample code must not use internal APIs.
     */
    private static DescribedPredicate<JavaClass> allowedOtelTraceparentReaderDependent() {
        return new DescribedPredicate<>("be an allowed OtelTraceparentReaderImpl dependent") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.core.manual.")
                        || name.startsWith("space.br1440.platform.tracing.core.propagation.")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }
}
