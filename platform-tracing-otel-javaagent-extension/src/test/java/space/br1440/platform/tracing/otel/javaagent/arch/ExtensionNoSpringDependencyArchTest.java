package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Архитектурный инвариант Фазы 15: {@code platform-tracing-otel-javaagent-extension} не зависит от Spring.
 * <p>
 * Расширение грузится изолированным ExtensionClassLoader'ом OTel Java Agent — до и независимо от
 * Spring-контекста. Любая зависимость от {@code org.springframework..} в продакшен-классах
 * расширения сломала бы загрузку в agent-режиме (Spring там отсутствует) и нарушила бы
 * dual-channel контракт ({@code ADR-dual-channel-properties}): extension читает только
 * {@code ConfigProperties}/env, а не Spring {@code Environment}.
 */
class ExtensionNoSpringDependencyArchTest {

    @Test
    void расширение_не_зависит_от_spring() {
        JavaClasses extensionClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.tracing.otel.javaagent");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("расширение грузится ExtensionClassLoader'ом OTel Java Agent без Spring; "
                        + "конфигурация — только через ConfigProperties/env (ADR-dual-channel-properties)");

        rule.check(extensionClasses);
    }

    /**
     * PR-2 guardrail: domain config classes ({@code *ExtensionConfig} кроме самого {@code ExtensionConfig}
     * и {@code ExtensionConfigReader}) не должны держать или импортировать {@link io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties}.
     * {@code ExtensionConfig} и {@code ExtensionConfigReader} явно исключены — они легально зависят от него.
     */
    @Test
    void domain_configs_do_not_depend_on_ConfigProperties() {
        JavaClasses configPackageClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.tracing.otel.javaagent.configuration");

        ArchRule rule = noClasses()
                .that()
                .resideInAPackage("space.br1440.platform.tracing.otel.javaagent.configuration")
                .and().haveSimpleNameEndingWith("ExtensionConfig")
                .and().doNotHaveSimpleName("ExtensionConfig")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName("io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties")
                .because("domain config classes получают значения через ExtensionConfigReader; "
                        + "они не должны держать ConfigProperties напрямую (PR-2 invariant)");

        rule.check(configPackageClasses);
    }
}
