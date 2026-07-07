package space.br1440.platform.tracing.otel.extension.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

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
        packages = "space.br1440.platform.tracing.otel.extension",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class SafeBoundaryArchTest {

    @ArchTest
    static final ArchRule safe_wrappers_do_not_use_standard_streams =
            noClasses()
                    .that().resideInAnyPackage(
                            "..safety..",
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
                    .that().haveSimpleName("PlatformTracingJmxRegistrar")
                    .should().resideInAPackage("..jmx..")
                    .because("PlatformTracingJmxRegistrar — единственный владелец пакетной JMX-регистрации "
                            + "шести доменных MBean; он живёт в пакете jmx как единственная точка входа "
                            + "для all-or-nothing batch registration с rollback");

    @ArchTest
    static final ArchRule no_public_registration_helper_in_production =
            noClasses()
                    .should().haveSimpleName("PlatformTracingControlRegistration")
                    .because("публичный registration-хелпер удалён; регистрацией MBean владеет "
                            + "PlatformTracingJmxRegistrar, а PlatformTracingControl не имеет registration API");

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
                            + "уже инициализированные константы (PlatformTracingObjectNames.SAMPLING, ...)");
}
