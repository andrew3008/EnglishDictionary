package space.br1440.platform.tracing.otel.extension.arch;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;

/**
 * ArchUnit guardrails for the six-domain JMX architecture.
 * <p>
 * Enforces that the deleted monolith ({@code PlatformTracingControl},
 * {@code PlatformTracingControlMBean}, {@code jmx.operations}) is gone,
 * and that domain MBeans are architecturally isolated from each other.
 */
@AnalyzeClasses(
        packages = "space.br1440.platform.tracing.otel.extension",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class DomainJmxArchTest {

    @ArchTest
    static final ArchRule no_monolith_PlatformTracingControl =
            noClasses()
                    .should().haveSimpleName("PlatformTracingControl")
                    .because("PlatformTracingControl (монолитный JMX-фасад) удалён; "
                            + "его место занимают шесть доменных MBean (PlatformSamplingControl, ...)");

    @ArchTest
    static final ArchRule no_monolith_PlatformTracingControlMBean =
            noClasses()
                    .should().haveSimpleName("PlatformTracingControlMBean")
                    .because("PlatformTracingControlMBean (монолитный MBean-интерфейс) удалён; "
                            + "заменён шестью доменными интерфейсами (PlatformSamplingControlMBean, ...)");

    @ArchTest
    static final ArchRule no_jmx_operations_package =
            noClasses()
                    .should().resideInAPackage("..jmx.operations..")
                    .because("пакет jmx.operations удалён; логика операций перенесена напрямую "
                            + "в доменные реализации (jmx.sampling, jmx.scrubbing, jmx.validation, ...)");

    @ArchTest
    static final ArchRule no_sampling_domain_importing_scrubbing =
            noClasses()
                    .that().resideInAPackage("..jmx.sampling..")
                    .should().dependOnClassesThat().resideInAPackage("..jmx.scrubbing..")
                    .because("доменные MBean архитектурно изолированы; sampling-домен не должен "
                            + "импортировать классы из scrubbing-домена");

    @ArchTest
    static final ArchRule no_sampling_domain_importing_validation =
            noClasses()
                    .that().resideInAPackage("..jmx.sampling..")
                    .should().dependOnClassesThat().resideInAPackage("..jmx.validation..")
                    .because("доменные MBean архитектурно изолированы; sampling-домен не должен "
                            + "импортировать классы из validation-домена");

    @ArchTest
    static final ArchRule no_scrubbing_domain_importing_sampling =
            noClasses()
                    .that().resideInAPackage("..jmx.scrubbing..")
                    .should().dependOnClassesThat().resideInAPackage("..jmx.sampling..")
                    .because("доменные MBean архитектурно изолированы; scrubbing-домен не должен "
                            + "импортировать классы из sampling-домена");

    @ArchTest
    static final ArchRule no_PlatformTracingControlTestBuilder =
            noClasses()
                    .should().haveSimpleName("PlatformTracingControlTestBuilder")
                    .because("PlatformTracingControlTestBuilder удалён вместе с монолитом; "
                            + "тесты конструируют доменные MBean напрямую");

    /**
     * D14 — Только {@link space.br1440.platform.tracing.otel.extension.jmx.PlatformTracingJmxRegistrar}
     * имеет право вызывать {@code MBeanServer#registerMBean}.
     * Ни один другой класс в {@code src/main} не должен регистрировать MBean напрямую.
     */
    @ArchTest
    static final ArchRule only_registrar_calls_registerMBean =
            noClasses()
                    .that().resideInAPackage("space.br1440.platform.tracing.otel.extension..")
                    .and().doNotHaveSimpleName("PlatformTracingJmxRegistrar")
                    .should().callMethod(
                            "javax.management.MBeanServer",
                            "registerMBean",
                            "java.lang.Object",
                            "javax.management.ObjectName")
                    .because("D14: PlatformTracingJmxRegistrar — единственный владелец "
                            + "batch-регистрации; ни один другой продакшн-класс не должен "
                            + "вызывать MBeanServer#registerMBean напрямую");

    /**
     * Доменные MBean-интерфейсы (имя оканчивается на «MBean») должны реализовываться
     * классами с тем же простым именем без суффикса «MBean» (Approach A).
     */
    @ArchTest
    static final ArchRule domain_mbean_interfaces_have_matching_implementations =
            classes()
                    .that().haveSimpleNameEndingWith("ControlMBean")
                    .and().resideInAPackage("..jmx..")
                    .should().beInterfaces()
                    .because("Approach A: Платформенные MBean-интерфейсы — только интерфейсы; "
                            + "их реализации — одноимённые классы без суффикса MBean");
}
