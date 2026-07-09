package space.br1440.platform.tracing.test.arch;

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
     * <p>
     * Packages may not exist yet — {@code allowEmptyShould(true)} keeps CI green until extraction PRs
     * create them.
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
     * PR-9B: entire core module main sources must not use JMX (control plane lives in otel-extension).
     */
    public static final ArchRule CORE_MAIN_NO_JMX = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.core..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("javax.management..")
            .because("platform-tracing-core must not depend on JMX — runtime control is agent-side only");

    // -- Слоистые границы пакета core.sampling: model / policy / engine / config -------------------

    /**
     * {@code core.sampling.model} — чистый слой состояния: не зависит от policy/engine/config.
     */
    public static final ArchRule SAMPLING_MODEL_IS_PURE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.model — чистый слой состояния, без зависимостей на policy/engine/properties");

    /**
     * {@code core.sampling.properties} зависит только от model (нормализация/валидация config-time),
     * но не от policy/engine.
     */
    public static final ArchRule SAMPLING_PROPERTIES_DEPENDS_ONLY_ON_MODEL = noClasses()
            .that().resideInAPackage("..core.sampling.properties..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("core.sampling.properties — compile-time нормализация/валидация; зависит только от model");

    /**
     * {@code core.sampling.policy} — семантический слой правил: зависит от model, но не от engine/properties.
     */
    public static final ArchRule SAMPLING_POLICY_NO_ENGINE_OR_CONFIG = noClasses()
            .that().resideInAPackage("..core.sampling.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.policy — семантический слой правил; зависит только от model");

    /**
     * {@code core.sampling.model} не зависит от runtime-state инфраструктуры API.
     */
    public static final ArchRule SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .because("core.sampling.model — чистый domain compile state; не зависит от api.runtime.state");

    /**
     * Только holder-managed production-снимки могут реализовывать {@code VersionedState}.
     */
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

    /**
     * Application autoconfigure/starter не должны зависеть от runtime-state holder-примитива.
     */
    public static final ArchRule APP_MODULES_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.starter..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .allowEmptyShould(true)
            .because("api.runtime.state — agent/runtime infrastructure, не SDK для autoconfigure");

    /**
     * Запрет возврата устаревшего пакета {@code api.config}.
     */
    public static final ArchRule NO_API_CONFIG_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.config..")
            .because("api.config удалён; используйте api.runtime.state.VersionedState/VersionedStateHolder");

    /**
     * Запрет возврата удалённого legacy-стека {@code api.span.builder.*} (PR-5, Fable_5 v1.2).
     * <p>
     * Пакет удалён вместе с {@code core.span.legacy}; typed builders для v3 живут в
     * {@code api.manual.*}. Правило ловит как повторное копирование legacy-классов, так и
     * восстановление пакета из старой ветки.
     */
    public static final ArchRule NO_LEGACY_SPAN_BUILDER_API = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.span.builder..")
            .because("api.span.builder.* удалён в рефакторинге Fable_5 v1.2; используйте api.manual.*");

    /**
     * Holder-managed snapshots остаются иммутабельными (все поля final).
     */
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

    /**
     * Реализации {@code SamplingPolicyRule} живут только в {@code core.sampling.policy}.
     * <p>
     * Делает «public, но not extension API» статус интерфейса машинно-проверяемым: новый rule-класс
     * нельзя добавить вне policy. Интерфейс сам себя не реализует, поэтому под правило не попадает.
     */
    public static final ArchRule SAMPLING_RULE_IMPLS_ONLY_IN_POLICY = classes()
            .that().implement("space.br1440.platform.tracing.core.sampling.policy.SamplingPolicyRule")
            .should().resideInAPackage("..core.sampling.policy..")
            .allowEmptyShould(true)
            .because("реализации SamplingPolicyRule — не extension API; допустимы только в core.sampling.policy");

    /**
     * {@code ProductionSamplingPolicyChain} публичен по необходимости компиляции (движок в соседнем
     * пакете), но НЕ является extension API: зависеть от него могут только policy и engine.
     */
    public static final ArchRule PRODUCTION_CHAIN_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.sampling.policy.ProductionSamplingPolicyChain")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("ProductionSamplingPolicyChain public только ради cross-package компиляции движка; "
                    + "не является публичным extension API");

    // -- PR-1: границы пакетов enrichment / naming / semconv.policy ------------------------------

    /**
     * {@code core.enrichment} не зависит от legacy builders и v3 manual transport.
     */
    public static final ArchRule CORE_ENRICHMENT_NO_MANUAL_OR_LEGACY = noClasses()
            .that().resideInAPackage("..core.enrichment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.span.legacy..",
                    "..core.manual..")
            .allowEmptyShould(true)
            .because("core.enrichment — agent-first обогащение; не зависит от manual/legacy builders");

    /**
     * {@code core.naming} — чистое именование span'ов: только OTel common, без trace/context.
     */
    public static final ArchRule CORE_NAMING_NO_OTEL_TRACE_CONTEXT = noClasses()
            .that().resideInAPackage("..core.naming..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.api.trace..",
                    "io.opentelemetry.context..")
            .allowEmptyShould(true)
            .because("core.naming не импортирует OTel trace/context API");

    /**
     * {@code core.semconv.policy} — политика атрибутов: только OTel common, без trace/context/sdk.
     */
    public static final ArchRule CORE_SEMCONV_POLICY_OTEL_COMMON_ONLY = noClasses()
            .that().resideInAPackage("..core.semconv.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.api.trace..",
                    "io.opentelemetry.context..",
                    "io.opentelemetry.sdk..")
            .allowEmptyShould(true)
            .because("core.semconv.policy допускает только io.opentelemetry.api.common");
}
