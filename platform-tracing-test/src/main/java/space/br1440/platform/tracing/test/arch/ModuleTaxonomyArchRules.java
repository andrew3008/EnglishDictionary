package space.br1440.platform.tracing.test.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.lang.ArchRule;

/**
 * Guardrails модульной таксономии PR-1 (migration-first стратегия сохранения границ).
 * <p>
 * Правила обеспечивают соблюдение <b>forward</b>-зависимостей. Известные pre-existing нарушения
 * вне их scope (например, {@code platform-tracing-otel} с {@code api opentelemetry-api})
 * задокументированы как MIGRATION_RISK в
 * {@code docs/architecture/platform-tracing-module-taxonomy.md} и не приводят к падению CI.
 *
 * @see docs/architecture/platform-tracing-module-taxonomy.md
 */
public final class ModuleTaxonomyArchRules {

    private ModuleTaxonomyArchRules() {
    }

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
     * Request id validation is a core invariant, not an api SPI.
     */
    public static final ArchRule API_HAS_NO_REQUEST_ID_SUPPORT_SPI = noClasses()
            .that(requestIdSupportApiTypes())
            .should().resideInAPackage("..api..")
            .allowEmptyShould(true)
            .because("RequestIdSupport SPI/holder удалены; request-id sanitization живёт в core.propagation");

    /**
     * API не должен разрешать implementation через ServiceLoader.
     */
    public static final ArchRule API_NO_SERVICE_LOADER = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().haveFullyQualifiedName("java.util.ServiceLoader")
            .because("implementation selection belongs to composition roots, not API class initialization");

    /**
     * API main-sources не зависят от implementation-модулей ни напрямую, ни через будущий package root.
     */
    public static final ArchRule API_MAIN_NO_IMPLEMENTATION_DEPENDENCY = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.api..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.core..",
                    "space.br1440.platform.tracing.otel..",
                    "space.br1440.platform.tracing.autoconfigure..")
            .because("platform-tracing-api содержит только стабильные контракты и value types");

    /**
     * API остаётся свободным от OTel и application-framework типов.
     */
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
            .because("public API должен оставаться OTel-free и framework-free");

    /**
     * The core request-id utility must stay dependency-light and framework-free.
     */
    public static final ArchRule REQUEST_ID_SUPPORT_CORE_UTILITY_IS_DEPENDENCY_LIGHT = noClasses()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.RequestIdSupport")
            .should().dependOnClassesThat(requestIdSupportForbiddenDependencies())
            .allowEmptyShould(true)
            .because("RequestIdSupport is a pure sanitizer/generator utility without OTel, Spring, Servlet, "
                    + "Reactor, or ServiceLoader coupling");

    /**
     * Autoconfigure приложения не должен зависеть от реализации agent-extension.
     * <p>
     * Agent extension загружается в ExtensionClassLoader; App CL не должен импортировать
     * production-классы {@code platform-tracing-otel-javaagent-extension} напрямую.
     */
    public static final ArchRule AUTOCONFIGURE_MAIN_NO_OTEL_EXTENSION_IMPL = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.autoconfigure..")
            .and().resideOutsideOfPackage("..test..")
            .and().resideOutsideOfPackage("..tests..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.otel.extension..")
            .allowEmptyShould(true)
            .because("platform-tracing-spring-boot-autoconfigure (App CL) не должен зависеть от "
                    + "platform-tracing-otel-javaagent-extension (Agent CL) implementation-классов");

    /**
     * Web-модули компилируются только против API и публичной границы autoconfigure.
     */
    public static final ArchRule WEB_AUTOCONFIGURE_MAIN_NO_CORE_IMPL = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..")
            .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.tracing.core..")
            .allowEmptyShould(true)
            .because("web autoconfigure не должен раскрывать platform-tracing-otel на compile classpath потребителя");

    /**
     * Оба web-модуля зависят только от API и boundary-support autoconfigure, но не от implementation packages.
     */
    public static final ArchRule WEB_MAIN_NO_IMPLEMENTATION_DEPENDENCY = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat().resideInAnyPackage(
                    "space.br1440.platform.tracing.core..",
                    "space.br1440.platform.tracing.otel..")
            .because("web integration использует public API и approved autoconfigure boundary-support");

    /** Web integration не управляет OTel Context напрямую. */
    public static final ArchRule WEB_MAIN_NO_OTEL_CONTEXT = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry.context..")
            .because("OTel Context ownership принадлежит implementation/composition layer");

    /** Web integration не должна обходить OTel-free propagation port. */
    public static final ArchRule WEB_MAIN_NO_INTERNAL_PROPAGATION_TYPES = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure.servlet..",
                    "space.br1440.platform.tracing.autoconfigure.reactive..",
                    "space.br1440.platform.tracing.webflux..")
            .should().dependOnClassesThat(internalPropagationType())
            .because("web integration вызывает PlatformOutboundPropagation, а не internal OTel infrastructure");

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
            .because("platform-tracing-otel не должен зависеть от JMX — управление runtime является agent-side");

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

    /** {@code core.sampling.model} не зависит от holder-managed runtime-state инфраструктуры. */
    public static final ArchRule SAMPLING_MODEL_NOT_DEPEND_ON_VERSIONED_STATE = noClasses()
            .that().resideInAPackage("..core.sampling.model..")
            .should().dependOnClassesThat().resideInAnyPackage("..core.runtime.versioned..")
            .because("core.sampling.model — чистый domain compile state; не зависит от core.runtime.versioned");

    /** Только holder-managed production-снимки могут реализовывать {@code VersionedState}. */
    public static final ArchRule VERSIONED_STATE_IMPLS_ALLOWLIST = classes()
            .that().implement("space.br1440.platform.tracing.core.runtime.versioned.VersionedState")
            .and().resideOutsideOfPackage("..test..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.extension.sampler.SamplerState")
            .orShould().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.otel.extension.scrubbing.ScrubbingSnapshot")
            .orShould().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.validation.ValidationSnapshot")
            .because("VersionedState — контракт holder-managed runtime state, а не generic HasVersion-маркер");

    /** Autoconfigure/starter приложения не должен зависеть от holder-managed versioned runtime-state. */
    public static final ArchRule APP_MODULES_NOT_DEPEND_ON_CORE_RUNTIME_VERSIONED = noClasses()
            .that().resideInAnyPackage(
                    "space.br1440.platform.tracing.autoconfigure..",
                    "space.br1440.platform.tracing.starter..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAnyPackage("..core.runtime.versioned..")
            .allowEmptyShould(true)
            .because("core.runtime.versioned — agent/runtime инфраструктура, а не SDK для autoconfigure");

    /** Regression guard: пакет {@code api.config} удалён; запрет любых зависимостей (cherry-pick / copy-paste). */
    public static final ArchRule NO_API_CONFIG_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.config..")
            .because("api.config удалён; используйте core.runtime.versioned.VersionedState/VersionedStateHolder");

    /** Regression guard: пакет {@code api.runtime.state} удалён; запрет любых зависимостей. */
    public static final ArchRule NO_API_RUNTIME_STATE_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.runtime.state..")
            .because("api.runtime.state удалён; CAS-примитив живёт в core.runtime.versioned");

    /** {@code VersionedState} и {@code VersionedStateHolder} — только в {@code core.runtime.versioned}. */
    public static final ArchRule VERSIONED_STATE_PRIMITIVE_ONLY_IN_CORE = classes()
            .that().haveSimpleName("VersionedState")
            .or().haveSimpleName("VersionedStateHolder")
            .and().resideOutsideOfPackage("..test..")
            .should().resideInAPackage("..core.runtime.versioned..")
            .allowEmptyShould(true)
            .because("VersionedState/VersionedStateHolder — agent-internal CAS primitive; только core.runtime.versioned");

    /**
     * Запрет возврата удалённого пакета {@code api.manual.*}.
     * <p>
     * Typed span builders для v3 живут в {@code api.span.builder.*}. Правило ловит
     * повторное копирование старого пакета из предыдущей ветки.
     */
    public static final ArchRule NO_API_MANUAL_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().resideInAPackage("..api.manual..")
            .because("api.manual.* переименован в api.span.builder.*; используйте api.span.builder.*");

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
     * Дополняет package-private visibility машинной проверкой: platform rule нельзя
     * перенести из policy-пакета или незаметно заменить внешней реализацией.
     */
    public static final ArchRule SAMPLING_RULE_IMPLS_ONLY_IN_POLICY = classes()
            .that().implement("space.br1440.platform.tracing.core.sampling.policy.SamplingPolicyRule")
            .should().resideInAPackage("..core.sampling.policy..")
            .because("SamplingPolicyRule является внутренним контрактом; реализации допустимы только в core.sampling.policy");

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

    // -- Граница публичного span API ---------------------------------------------------------------

    /** Публичный API не раскрывает типы {@code io.opentelemetry.api}; Context-контракты проверяются отдельно. */
    public static final ArchRule API_MAIN_NO_OTEL_API = noClasses()
            .that().resideInAPackage("..api..")
            .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry.api..")
            .because("OTel API остаётся деталью core; публичные enrichment/semconv контракты platform-owned");

    /** Public SpanFactory API и builders не раскрывают implementation-side reader type. */
    public static final ArchRule SPAN_FACTORY_API_NO_TRACEPARENT_READER = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.api.span..")
            .and().arePublic()
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.OtelTraceparentReader")
            .because("OtelTraceparentReader внедряется только во внутреннюю DefaultSpanFactory composition");

    /** CP-C2 propagation port остаётся интерфейсом в API. */
    public static final ArchRule PROPAGATION_PORT_OWNERSHIP = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation")
            .should().beInterfaces()
            .andShould().resideInAPackage("space.br1440.platform.tracing.api.propagation.control")
            .because("CP-C2 contract принадлежит API, а concrete implementations — core.propagation.control");

    /** Запрещённые identity infrastructure types не становятся публичной поверхностью. */
    public static final ArchRule IDENTITY_INTERNAL_TYPES_NOT_PUBLIC = noClasses()
            .that().arePublic()
            .should().haveNameMatching(
                    ".*\\.(RequestContextAccessor|RequestIdentityAccessor|RequestIdentityContext|"
                            + "RequestIdentityBinder|RequestIdentityScope)")
            .because("CP-1 R2 оставляет identity store/binder infrastructure внутренней");

    /** Удалённый пакет санитайзеров не должен возвращаться в публичный API. */
    public static final ArchRule NO_API_SPAN_SANITIZE_PACKAGE = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().resideInAPackage("..api.span.sanitize..")
            .allowEmptyShould(true)
            .because("санитайзеры являются implementation utility и живут рядом с internal caller");

    /** URL sanitizer допустим только рядом с единственным caller в {@code core.manual}. */
    public static final ArchRule URL_SANITIZER_ONLY_IN_CORE_MANUAL = classes()
            .that().haveSimpleName("UrlSanitizer")
            .and().resideOutsideOfPackage("..test..")
            .should().resideInAPackage("..core.manual..")
            .allowEmptyShould(true)
            .because("UrlSanitizer не является публичным API или extension point");

    /** Неиспользуемый SQL sanitizer удалён без замещающего API. */
    public static final ArchRule NO_SQL_SANITIZER = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().haveSimpleName("SqlSanitizer")
            .allowEmptyShould(true)
            .because("SQL sanitizer не имел production callers и не должен возвращаться");

    /** OTel-based category enrichment удалён без aliases и deprecated bridges. */
    public static final ArchRule NO_PUBLIC_SPAN_ENRICHMENT = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.api.span.enrich.SpanEnrichment")
            .allowEmptyShould(true)
            .because("runtime enrichment ограничен platform-safe GenericSpanEnrichment");

    /** Category marker больше не участвует в runtime-контракте. */
    public static final ArchRule NO_PLATFORM_SPAN_CONTEXT_KEYS = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().haveSimpleName("PlatformSpanContextKeys")
            .allowEmptyShould(true)
            .because("category marker path удалён вместе с category-specific enrichment");

    // -- PR-3: api.propagation.control / core.propagation.control guardrails -----------------------

    /**
     * Реализации control-интерфейсов propagation живут только в {@code core.propagation.control}.
     * <p>
     * Исключены анонимные и вложенные классы (inline test doubles), а также тестовые пакеты.
     */
    public static final ArchRule CONTROL_IMPLS_ONLY_IN_CORE = classes()
            .that().implement("space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation")
            .or().implement("space.br1440.platform.tracing.api.propagation.control.InboundTraceControlExtractor")
            .and().areNotAnonymousClasses()
            .and().areNotMemberClasses()
            .and().resideOutsideOfPackage("..test..")
            .and().haveSimpleNameNotEndingWith("Test")
            .should().resideInAPackage("..core.propagation.control..")
            .because("реализации OutboundPropagationPolicy / TrustedDestinationMatcher / "
                    + "PlatformOutboundPropagation / InboundTraceControlExtractor — только в core.propagation.control");

    /**
     * {@code TrustedDestinationMatchers} — public factory для wiring; не для starter'ов и бизнес-модулей.
     */
    public static final ArchRule CONTROL_IMPL_ACCESS_RESTRICTED = classes()
            .that().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.core.propagation.control.TrustedDestinationMatchers")
            .should().onlyHaveDependentClassesThat(allowedTrustedDestinationMatchersDependent())
            .allowEmptyShould(true)
            .because("TrustedDestinationMatchers — wiring factory; допустима только из core, autoconfigure, "
                    + "otel.extension и test");

    /**
     * Запрет возврата concrete impl в {@code api.propagation.control} (откат PR-1/PR-2).
     * <p>
     * Допускаются: interfaces, records и enums.
     */
    public static final ArchRule API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL = noClasses()
            .that().resideInAPackage("..api.propagation.control..")
            .and().areNotInterfaces()
            .and().areNotRecords()
            .and().areNotEnums()
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("api.propagation.control — только интерфейсы, records и enums");

    // -- PR-3 (api.mdc split guardrails) ----------------------------------------------------------

    /**
     * {@code api.mdc} должен содержать только контракты: utility-холдеры констант ({@code *Keys},
     * {@code *Constants}) и интерфейсы / {@code @FunctionalInterface}.
     * <p>
     * Запрещены любые concrete-классы (кроме utility-holders по имени), SLF4J-импорты, mutable state.
     * Паттерн — аналог {@link #API_PROPAGATION_CONTROL_NO_CONCRETE_IMPL}:
     * исключить utility-holders по имени, требовать interface для остального.
     * <p>
     * {@code TracingMdcKeys} — {@code public final class} с private ctor (utility-holder),
     * поэтому исключается через {@code haveSimpleNameNotContaining("Keys")}.
     */
    public static final ArchRule API_MDC_CONTRACTS_ONLY = noClasses()
            .that().resideInAPackage("..api.mdc..")
            .and().areNotInterfaces()
            .and().areNotAnnotations()
            .and().areNotRecords()
            .and().areNotEnums()
            .and().haveSimpleNameNotContaining("Keys")
            .and().haveSimpleNameNotContaining("Constants")
            .should().beInterfaces()
            .allowEmptyShould(true)
            .because("api.mdc — только контракты: интерфейсы/@FunctionalInterface и utility *Keys/*Constants. "
                    + "SLF4J-мост, TraceMirror и конкретные реализации живут в core.mdc.remote");

    /**
     * {@code RemoteServiceTraceMirror} — package-private инфраструктура; не должен быть public.
     * <p>
     * Использует {@code bePackagePrivate()} (не {@code areNotPublic()}) — иначе пропустит protected.
     */
    public static final ArchRule TRACE_MIRROR_PACKAGE_PRIVATE = classes()
            .that().haveSimpleName("RemoteServiceTraceMirror")
            .and().resideOutsideOfPackage("..test..")
            .should().bePackagePrivate()
            .allowEmptyShould(true)
            .because("RemoteServiceTraceMirror — internal trace-scoped map; "
                    + "пишет только RemoteServiceMdc, читает только RemoteServiceNameResolver — оба в одном пакете");

    /**
     * {@code RemoteServiceMdc} и {@code RemoteServiceNameResolver} живут только в
     * {@code core.mdc.remote}. Запрещает случайный возврат implementation-типов в api.mdc.
     */
    public static final ArchRule REMOTE_SERVICE_MDC_IMPL_ONLY_IN_CORE = classes()
            .that(new DescribedPredicate<>("be RemoteServiceMdc or RemoteServiceNameResolver outside tests") {
                @Override
                public boolean test(JavaClass input) {
                    String simpleName = input.getSimpleName();
                    return ("RemoteServiceMdc".equals(simpleName) || "RemoteServiceNameResolver".equals(simpleName))
                            && !input.getPackageName().contains(".test");
                }
            })
            .should().resideInAPackage("..core.mdc.remote..")
            .allowEmptyShould(true)
            .because("RemoteServiceMdc и RemoteServiceNameResolver — implementation; "
                    + "только core.mdc.remote (не api.mdc)");

    /**
     * Запрет возврата удалённого anti-pattern {@code RemoteServiceContextReaders}
     * (global mutable static registry, удалён в PR-2).
     */
    public static final ArchRule NO_REMOTE_SERVICE_CONTEXT_READERS = noClasses()
            .that().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat().haveFullyQualifiedName(
                    "space.br1440.platform.tracing.api.mdc.RemoteServiceContextReaders")
            .allowEmptyShould(true)
            .because("RemoteServiceContextReaders удалён в PR-2 (anti-pattern: mutable global static registry); "
                    + "используйте ObjectProvider<RemoteServiceNameSource>");

    /**
     * otel-extension main-sources: из {@code ..api.mdc..} разрешены только
     * {@code TracingMdcKeys} и {@code RemoteServiceNameSource}.
     * Любой другой api.mdc-тип (вернувшийся после рефакторинга) — нарушение.
     * <p>
     * Правило запрещает зависимость на api.mdc целиком, кроме двух разрешённых FQN.
     * Техника: {@code noClasses().should().dependOnClassesThat()} с предикатом-исключением.
     */
    public static final ArchRule OTEL_EXTENSION_MDC_FROM_CORE = noClasses()
            .that().resideInAPackage("space.br1440.platform.tracing.otel.extension..")
            .and().resideOutsideOfPackage("..test..")
            .should().dependOnClassesThat(apiMdcExceptAllowed())
            .allowEmptyShould(true)
            .because("otel-extension использует MDC только через core.mdc.remote.RemoteServiceMdc; "
                    + "из api.mdc разрешены только TracingMdcKeys и RemoteServiceNameSource");

    private static DescribedPredicate<JavaClass> allowedOtelTraceparentReaderDependent() {
        return new DescribedPredicate<>("быть разрешённым зависимым от OtelTraceparentReaderImpl") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.core.propagation.")
                        || name.equals("space.br1440.platform.tracing.core.manual.DefaultSpanFactory")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }

    private static DescribedPredicate<JavaClass> requestIdSupportApiTypes() {
        return new DescribedPredicate<>("be RequestIdSupport* in api propagation") {
            @Override
            public boolean test(JavaClass input) {
                return input.getPackageName().contains(".api.propagation")
                        && input.getSimpleName().startsWith("RequestIdSupport");
            }
        };
    }

    private static DescribedPredicate<JavaClass> requestIdSupportForbiddenDependencies() {
        return new DescribedPredicate<>("be forbidden RequestIdSupport dependency") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                String pkg = input.getPackageName();
                return pkg.startsWith("io.opentelemetry.")
                        || pkg.startsWith("org.springframework.")
                        || pkg.startsWith("jakarta.servlet.")
                        || pkg.startsWith("reactor.")
                        || "java.util.ServiceLoader".equals(name);
            }
        };
    }

    private static DescribedPredicate<JavaClass> internalPropagationType() {
        return new DescribedPredicate<>("быть internal propagation infrastructure") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return "space.br1440.platform.tracing.core.propagation.control.TraceControlHeaderInjector"
                                .equals(name)
                        || "space.br1440.platform.tracing.core.propagation.control.PlatformTraceContextKeys"
                                .equals(name)
                        || "space.br1440.platform.tracing.core.propagation.OtelTraceparentReader"
                                .equals(name);
            }
        };
    }

    private static DescribedPredicate<JavaClass> allowedTrustedDestinationMatchersDependent() {
        return new DescribedPredicate<>("быть разрешённым зависимым от TrustedDestinationMatchers") {
            @Override
            public boolean test(JavaClass input) {
                String name = input.getName();
                return name.startsWith("space.br1440.platform.tracing.core.")
                        || name.startsWith("space.br1440.platform.tracing.autoconfigure.")
                        || name.startsWith("space.br1440.platform.tracing.otel.extension.")
                        || name.contains(".test.")
                        || name.endsWith("Test");
            }
        };
    }

    /**
     * Предикат: класс находится в {@code ..api.mdc..} И не является одним из двух разрешённых
     * контрактных типов ({@code TracingMdcKeys}, {@code RemoteServiceNameSource}).
     */
    private static DescribedPredicate<JavaClass> apiMdcExceptAllowed() {
        return new DescribedPredicate<>("находиться в ..api.mdc.. и не быть TracingMdcKeys/RemoteServiceNameSource") {
            @Override
            public boolean test(JavaClass input) {
                String pkg = input.getPackageName();
                if (!pkg.contains(".api.mdc")) {
                    return false;
                }
                String simpleName = input.getSimpleName();
                return !"TracingMdcKeys".equals(simpleName)
                        && !"RemoteServiceNameSource".equals(simpleName);
            }
        };
    }
}
