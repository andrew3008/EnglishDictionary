package space.br1440.platform.tracing.test.arch;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;
import lombok.experimental.UtilityClass;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Guardrails модульной таксономии PR-1 (migration-first стратегия сохранения границ).
 * <p>
 * Правила обеспечивают соблюдение <b>forward</b>-зависимостей. Известные pre-existing нарушения
 * вне их scope (например, {@code platform-tracing-core} с {@code api opentelemetry-api})
 * задокументированы как MIGRATION_RISK в
 * {@code docs/architecture/platform-tracing-module-taxonomy.md} и не приводят к падению CI.
 *
 * @see docs/architecture/platform-tracing-module-taxonomy.md
 */
@UtilityClass
public final class ModuleTaxonomyArchRules {

    /**
     * Парсинг traceparent делегирован OTel-backed bridge;
     * повторное введение публичных *Parser API в пакете api.propagation запрещено.
     */
    public static final ArchRule API_PROPAGATION_HAS_NO_PUBLIC_PARSERS = noClasses()
            .that().resideInAPackage("..api.propagation..")
            .and().arePublic()
            .should().haveSimpleNameEndingWith("Parser")
            .allowEmptyShould(true)
            .because("wire-парсинг propagation делегирован OTel-backed bridge'ам, а не публичным *Parser API");

    /**
     * {@code OtelTraceparentReaderImpl} живёт в core и не является extension API.
     * Зависимые классы должны располагаться в разрешённых core/test-пакетах.
     * Предикат основан на паттернах пакетов, чтобы избежать хрупких hardcoded FQN-списков.
     */
    public static final ArchRule OTEL_TRACEPARENT_READER_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.OtelTraceparentReaderImpl")
            .should().onlyHaveDependentClassesThat(allowedOtelTraceparentReaderDependent())
            .allowEmptyShould(true)
            .because("OtelTraceparentReaderImpl — внутренний bridge в core, а не extension API");

    /**
     * {@code RequestIdSupportImpl} живёт в core и не является extension API.
     * Допустимые зависимые: {@code core.propagation.*}, SPI-holder {@code RequestIdSupports},
     * autoconfigure-фильтры и тесты. Прямое использование в прикладном коде запрещено.
     */
    public static final ArchRule REQUEST_ID_SUPPORT_IMPL_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.RequestIdSupportImpl")
            .should().onlyHaveDependentClassesThat(allowedRequestIdSupportImplDependent())
            .allowEmptyShould(true)
            .because("RequestIdSupportImpl — внутренний bridge в core; доступ разрешён только через RequestIdSupports.get()");

    /**
     * Autoconfigure приложения не должен зависеть от реализации agent-extension.
     * <p>
     * Agent extension загружается в ExtensionClassLoader; App CL не должен импортировать
     * production-классы {@code platform-tracing-otel-extension} напрямую.
     */
    public static final ArchRule AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.otel.extension..")
            .allowEmptyShould(true)
            .because("platform-tracing-spring-boot-autoconfigure (App CL) не должен зависеть от "
                    + "platform-tracing-otel-extension (Agent CL) implementation-классов");

    /**
     * Servlet web autoconfigure не должен тянуть типы WebFlux/Reactor в main-sources.
     */
    public static final ArchRule WEBMVC_MAIN_NO_WEBFLUX_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.servlet..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "org.springframework.web.reactive..",
                    "org.springframework.http.server.reactive..",
                    "reactor.core..")
            .allowEmptyShould(true)
            .because("platform-tracing-autoconfigure-webmvc не должен зависеть от типов WebFlux/Reactor");

    /**
     * Reactive web autoconfigure не должен тянуть типы Servlet/MVC в main-sources.
     */
    public static final ArchRule WEBFLUX_MAIN_NO_SERVLET_STACK = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "jakarta.servlet..",
                    "org.springframework.web.servlet..")
            .allowEmptyShould(true)
            .because("platform-tracing-autoconfigure-webflux не должен зависеть от типов Servlet/MVC");

    /**
     * Будущие pure policy пакеты в core (цели экстракции после PR-6).
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
            .because("pure policy-пакеты core.{sampling,scrubbing,validation,enrichment} "
                    + "не должны зависеть от OTel, Spring или JMX (граница PR-9B)");

    /**
     * PR-9B: main-sources всего core-модуля не должны использовать JMX.
     */
    public static final ArchRule CORE_MAIN_NO_JMX = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.core..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("javax.management..")
            .because("platform-tracing-core не должен зависеть от JMX — управление runtime является agent-side");

    // -- Слоистые границы пакета core.sampling: model / policy / engine / config -------------------

    /** {@code core.sampling.model} — чистый слой состояния: не зависит от policy/engine/config. */
    public static final ArchRule SAMPLING_MODEL_IS_PURE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.model — чистый слой состояния, без зависимостей на policy/engine/properties");

    /** {@code core.sampling.properties} зависит только от model (нормализация/валидация в compile-time). */
    public static final ArchRule SAMPLING_PROPERTIES_DEPENDS_ONLY_ON_MODEL = noClasses()
            .that().resideInAPackage("..core.sampling.properties..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("core.sampling.properties — compile-time нормализация/валидация; зависит только от model");

    /** {@code core.sampling.policy} — семантический слой правил: зависит от model, но не от engine/properties. */
    public static final ArchRule SAMPLING_POLICY_NO_ENGINE_OR_CONFIG = noClasses()
            .that().resideInAPackage("..core.sampling.policy..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.sampling.engine..",
                    "..core.sampling.properties..")
            .because("core.sampling.policy — семантический слой правил; зависит только от model");

    /** {@code core.sampling.model} не зависит от runtime-state инфраструктуры API. */
    public static final ArchRule SAMPLING_MODEL_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .because("core.sampling.model — чистый domain compile state; не зависит от api.runtime.state");

    /** Только holder-managed production-снимки могут реализовывать {@code VersionedState}. */
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
            .because("VersionedState — контракт holder-managed runtime state, а не generic HasVersion-маркер");

    /** Autoconfigure/starter приложения не должен зависеть от holder-managed runtime-state. */
    public static final ArchRule APP_MODULES_NOT_DEPEND_ON_RUNTIME_STATE = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.starter..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("..api.runtime.state..")
            .allowEmptyShould(true)
            .because("api.runtime.state — agent/runtime инфраструктура, а не SDK для autoconfigure");

    /** Запрет возврата устаревшего пакета {@code api.config}. */
    public static final ArchRule NO_API_CONFIG_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.config..")
            .because("api.config удалён; используйте api.runtime.state.VersionedState/VersionedStateHolder");

    /**
     * Запрет возврата удалённого legacy-стека {@code api.span.builder.*} (PR-5, Fable_5 v1.2).
     * <p>
     * Пакет удалён вместе с {@code core.span.legacy}; typed builders для v3 живут в
     * {@code api.manual.*}. Правило ловит как повторное копирование legacy-классов,
     * так и восстановление пакета из старой ветки.
     */
    public static final ArchRule NO_LEGACY_SPAN_BUILDER_API = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.span.builder..")
            .because("api.span.builder.* удалён в рефакторинге Fable_5 v1.2; используйте api.manual.*");

    /** Holder-managed snapshots остаются иммутабельными (все поля final). */
    public static final ArchRule SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("SamplerState")
            .should().beFinal()
            .because("VersionedState snapshots должны оставаться иммутабельными (SamplerState)");

    /** Holder-managed snapshots остаются иммутабельными (все поля final). */
    public static final ArchRule SCRUBBING_SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("ScrubbingSnapshot")
            .should().beFinal()
            .because("VersionedState snapshots должны оставаться иммутабельными (ScrubbingSnapshot)");

    /** Holder-managed snapshots остаются иммутабельными (все поля final). */
    public static final ArchRule VALIDATION_SNAPSHOT_FIELDS_ARE_FINAL = com.tngtech.archunit.lang.syntax.ArchRuleDefinition.fields()
            .that().areDeclaredInClassesThat().haveSimpleName("ValidationSnapshot")
            .should().beFinal()
            .because("VersionedState snapshots должны оставаться иммутабельными (ValidationSnapshot)");

    /**
     * Реализации {@code SamplingPolicyRule} живут только в {@code core.sampling.policy}.
     * <p>
     * Делает статус «public, но не extension API» машинно-проверяемым: новый rule-класс
     * нельзя добавить вне policy-пакета. Сам интерфейс себя не реализует.
     */
    public static final ArchRule SAMPLING_RULE_IMPLS_ONLY_IN_POLICY = classes()
            .that().implement("space.br1440.platform.tracing.core.sampling.policy.SamplingPolicyRule")
            .should().resideInAPackage("..core.sampling.policy..")
            .allowEmptyShould(true)
            .because("реализации SamplingPolicyRule — не extension API; допустимы только в core.sampling.policy");

    /**
     * {@code ProductionSamplingPolicyChain} публичен только по необходимости компиляции
     * (движок в соседнем пакете), но НЕ является extension API: зависеть от него могут
     * только policy и engine.
     */
    public static final ArchRule PRODUCTION_CHAIN_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.sampling.policy.ProductionSamplingPolicyChain")
            .should().onlyHaveDependentClassesThat().resideInAnyPackage(
                    "..core.sampling.policy..",
                    "..core.sampling.engine..")
            .because("ProductionSamplingPolicyChain публичен только ради cross-package компиляции движка; "
                    + "не является публичным extension API");

    // -- PR-1: границы пакетов enrichment / naming / semconv.policy ------------------------------

    /** {@code core.enrichment} не зависит от legacy builders и v3 manual transport. */
    public static final ArchRule CORE_ENRICHMENT_NO_MANUAL_OR_LEGACY = noClasses()
            .that().resideInAPackage("..core.enrichment..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "..core.span.legacy..",
                    "..core.manual..")
            .allowEmptyShould(true)
            .because("core.enrichment — agent-first обогащение; не зависит от manual/legacy builders");

    /** {@code core.naming} — чистое именование span'ов: только OTel common, без trace/context. */
    public static final ArchRule CORE_NAMING_NO_OTEL_TRACE_CONTEXT = noClasses()
            .that().resideInAPackage("..core.naming..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "io.opentelemetry.api.trace..",
                    "io.opentelemetry.context..")
            .allowEmptyShould(true)
            .because("core.naming не импортирует OTel trace/context API");

    /** {@code core.semconv.policy} — политика атрибутов: только OTel common, без trace/context/sdk. */
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

    private static DescribedPredicate<JavaClass> allowedOtelTraceparentReaderDependent() {
        return new DescribedPredicate<>("быть разрешённым зависимым от OtelTraceparentReaderImpl") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.core.propagation.")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }

    private static DescribedPredicate<JavaClass> allowedRequestIdSupportImplDependent() {
        return new DescribedPredicate<>("быть разрешённым зависимым от RequestIdSupportImpl") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.core.propagation.")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }
}
