package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

/**
 * Архитектурный инвариант: {@code platform-tracing-otel-javaagent-extension} не зависит от Spring.
 * <p>
 * Расширение грузится изолированным ExtensionClassLoader'ом OTel Java Agent — до и независимо от
 * Spring-контекста. Любая зависимость от {@code org.springframework..} в продакшен-классах
 * расширения сломала бы загрузку в agent-режиме (Spring там отсутствует) и нарушила бы
 * dual-channel контракт ({@code ADR-dual-channel-properties}): extension читает только
 * {@code ConfigProperties}/env, а не Spring {@code Environment}.
 */
class ExtensionNoSpringDependencyArchTest {

    private static final String CONFIGURATION_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent.configuration";

    private static final String CONFIG_PROPERTIES =
            "io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties";

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

    @Test
    void domain_configs_do_not_depend_on_ConfigProperties() {
        ArchRule rule = noClasses()
                .that()
                .resideInAPackage(CONFIGURATION_PACKAGE)
                .and().haveSimpleNameEndingWith("ExtensionConfig")
                .and().doNotHaveSimpleName("ExtensionConfig")
                .and().doNotHaveSimpleName("SamplingExtensionConfig")
                .should().dependOnClassesThat()
                .haveFullyQualifiedName(CONFIG_PROPERTIES)
                .because("domain config classes получают значения через ExtensionConfigReader; "
                        + "они не должны держать ConfigProperties напрямую (PR-2 invariant)");

        rule.check(configurationPackageClasses());
    }

    @Test
    void domain_configs_do_not_hold_ConfigProperties_state() {
        ArchRule rule = noFields()
                .that()
                .areDeclaredInClassesThat().resideInAPackage(CONFIGURATION_PACKAGE)
                .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("ExtensionConfig")
                .should().haveRawType(CONFIG_PROPERTIES)
                .because("domain config — immutable типизированный снимок; удержание живого "
                        + "ConfigProperties стирает границу startup/runtime (S6 invariant)");

        rule.check(configurationPackageClasses());
    }

    private static JavaClasses configurationPackageClasses() {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(CONFIGURATION_PACKAGE);
    }
}
