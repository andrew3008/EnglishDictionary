package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import java.util.List;

import space.br1440.platform.tracing.otel.javaagent.configuration.MaskedSecret;
import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.exporter.SafeSpanExporter;
import space.br1440.platform.tracing.otel.javaagent.processor.ScrubbingSpanProcessor;
import space.br1440.platform.tracing.otel.javaagent.scrubbing.ScrubbingRuntime;

import io.opentelemetry.sdk.trace.export.SpanExporter;

import static com.tngtech.archunit.core.domain.JavaCall.Predicates.target;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.type;
import static com.tngtech.archunit.core.domain.properties.HasName.Predicates.nameStartingWith;
import static com.tngtech.archunit.core.domain.properties.HasOwner.Predicates.With.owner;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.GeneralCodingRules.ACCESS_STANDARD_STREAMS;

/**
 * ArchUnit-гардрейл safe-границ (Фаза 11).
 * <p>
 * Ошибки tracing-слоя должны логироваться через slf4j / {@code RateLimitedLogger} (rate-limited),
 * а не печататься в {@code System.out}/{@code System.err} и не через {@code Throwable.printStackTrace()}.
 * Правило предотвращает регресс вида «отладочный {@code printStackTrace} на hot-path», который
 * приводит к log storm и обходит троттлинг/диагностику.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.javaagent",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class SafeBoundaryArchTest {

    @ArchTest
    static final ArchRule safe_wrappers_do_not_use_standard_streams =
            noClasses()
                    .that().resideInAnyPackage(
                            "..resilience..",
                            "..diagnostics..",
                            "..sampler..",
                            "..propagation..",
                            "..exporter..",
                            "..processor..")
                    .should(ACCESS_STANDARD_STREAMS)
                    .because("ошибки tracing должны идти через slf4j/RateLimitedLogger, "
                            + "а не в System.out/err или printStackTrace (Фаза 11: no log storm, наблюдаемость через метрики)");

    @ArchTest
    static final ArchRule no_factory_spike_package_in_production =
            noClasses()
                    .should().resideInAPackage("..factory.spike..")
                    .because("factory.spike пакет удалён; classloader visibility probe перенесён в "
                            + "test-only extension JAR (platform-tracing-e2e-tests/src/testExtension)");

    @ArchTest
    static final ArchRule no_jmx_spike_package_in_production =
            noClasses()
                    .should().resideInAPackage("..jmx.spike..")
                    .because("jmx.spike транспорт удалён из production; JMX wire round-trip харнесс существует "
                            + "только как test-only OTel extension (platform-tracing-e2e-tests/src/jmxWireExtension)");

    @ArchTest
    static final ArchRule no_spike_named_classes_in_production =
            noClasses()
                    .should().haveSimpleNameContaining("Spike")
                    .because("Spike/debug-инструменты должны жить только в test/testExtension source sets, "
                            + "не в production src/main");

    @ArchTest
    static final ArchRule jmx_registrar_resides_in_jmx_package =
            classes()
                    .that().haveSimpleName("DomainMBeanRegistrar")
                    .should().resideInAPackage("..jmx..")
                    .because("DomainMBeanRegistrar — единственный владелец пакетной JMX-регистрации "
                            + "шести доменных MBean; он живёт в jmx.registration как единственная точка "
                            + "входа для all-or-nothing batch registration с rollback");

    @ArchTest
    static final ArchRule no_public_registration_helper_in_production =
            noClasses()
                    .should().haveSimpleName("PlatformTracingControlRegistration")
                    .because("публичный registration-хелпер удалён; регистрацией MBean владеет "
                            + "DomainMBeanRegistrar, а PlatformTracingControl не имеет registration API");

    /**
     * ObjectName-конструктор {@code new ObjectName(String)} должен вызываться только из
     * классов-констант ({@code *ObjectNames}). Это предотвращает разброс сырых строковых
     * литералов по кодовой базе и делает ObjectName-владение явным.
     *
     * <p>Реализация через ArchUnit callConstructor с именами классов в виде строк.
     */
    @ArchTest
    static final ArchRule objectName_constructor_only_in_constants_classes =
            noClasses()
                    .that().haveSimpleNameNotEndingWith("ObjectNames")
                    .should().callConstructor(
                            "javax.management.ObjectName",
                            "java.lang.String")
                    .because("сырые ObjectName-литералы допустимы только в *ObjectNames "
                            + "константных классах; все остальные классы должны использовать "
                            + "уже инициализированные константы (DomainObjectNames.SAMPLING, ...)");

    /**
     * ADR-scrubbing-hybrid-processor-export-boundary.md P4/P5: production assembly of
     * {@link SafeSpanExporter} with a live {@link ScrubbingRuntime} is exclusive to
     * {@code PlatformExportProcessorFactory}. Fixture constructors (delegate-only / no-op runtime)
     * must not appear on the production path.
     */
    @ArchTest
    static final ArchRule production_safe_span_exporter_is_assembled_only_by_export_factory =
            noClasses()
                    .that().doNotHaveSimpleName("PlatformExportProcessorFactory")
                    .and().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructor(
                            SafeSpanExporter.class,
                            SpanExporter.class,
                            TracingSettingsSource.class,
                            ScrubbingRuntime.class)
                    .because("единственная production-сборка SafeSpanExporter — PlatformExportProcessorFactory; "
                            + "обход без session-shared ScrubbingRuntime запрещён");

    @ArchTest
    static final ArchRule production_code_does_not_use_delegate_only_safe_span_exporter_constructor =
            noClasses()
                    .that().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructor(SafeSpanExporter.class, SpanExporter.class)
                    .because("одноаргументный конструктор SafeSpanExporter — test/fixture-only "
                            + "и подставляет disabled ScrubbingRuntime");

    @ArchTest
    static final ArchRule production_code_does_not_use_policy_only_safe_span_exporter_constructor =
            noClasses()
                    .that().doNotHaveSimpleName("SafeSpanExporter")
                    .should().callConstructor(
                            SafeSpanExporter.class,
                            SpanExporter.class,
                            TracingSettingsSource.class)
                    .because("двух-аргументный конструктор SafeSpanExporter — test/fixture-only "
                            + "и подставляет disabled ScrubbingRuntime");

    /**
     * Installation barrier — не общедоступный mutable объект: подтверждать установку могут
     * только сборщики pipeline'а. Иначе любой класс расширения мог бы объявить barrier полным
     * и получить {@code SANITIZER_INSTALLED} без реальной защиты пути экспорта.
     */
    @ArchTest
    static final ArchRule installation_barrier_is_acknowledged_only_by_pipeline_assembly =
            noClasses()
                    .that().doNotHaveSimpleName("PlatformExportProcessorFactory")
                    .and().doNotHaveSimpleName("PlatformSpanProcessorFactory")
                    .and().doNotHaveSimpleName("ScrubbingRuntime")
                    .should().callMethodWhere(
                            target(owner(type(ScrubbingRuntime.Installation.class)))
                                    .and(target(nameStartingWith("acknowledge"))))
                    .because("acknowledge* принадлежит сборке pipeline'а; произвольный вызов "
                            + "подтверждает installation barrier без реальной защиты экспорта");

    /**
     * {@code ScrubbingRuntimeSupport} и list-конструкторы {@code ScrubbingSpanProcessor} строят
     * собственный runtime вне сессии. Они публичны только потому, что нужны тестам, JMH и E2E из
     * других модулей; production-код обязан идти через {@code PlatformScrubberFactory}.
     */
    @ArchTest
    static final ArchRule production_code_builds_scrubbing_runtime_only_through_the_factory =
            noClasses()
                    .that().doNotHaveSimpleName("ScrubbingSpanProcessor")
                    .should().accessClassesThat().haveSimpleName("ScrubbingRuntimeSupport")
                    .because("единственная production-точка создания ScrubbingRuntime — "
                            + "PlatformScrubberFactory; ScrubbingRuntimeSupport остаётся "
                            + "test-fixture поверхностью для тестов, JMH и E2E");

    @ArchTest
    static final ArchRule production_code_does_not_use_rule_list_scrubbing_processor_constructors =
            noClasses()
                    .that().doNotHaveSimpleName("ScrubbingSpanProcessor")
                    .should().callConstructor(ScrubbingSpanProcessor.class, List.class)
                    .orShould().callConstructor(
                            ScrubbingSpanProcessor.class, List.class, MaskedSecret.class, boolean.class)
                    .because("list-конструкторы не участвуют в session-scoped installation barrier "
                            + "и допустимы только в тестовых сборках");
}
