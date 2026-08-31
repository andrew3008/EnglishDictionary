package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class AgentPlaneIsolationArchTest {

    private static final String EXTENSION_PACKAGE =
            "space.br1440.platform.tracing.otel.javaagent";

    private static final String CONFIGURATION_PACKAGE = EXTENSION_PACKAGE + ".configuration";

    private static final String CONFIG_PROPERTIES = ConfigProperties.class.getName();

    private static final JavaClasses EXTENSION_CLASSES = productionClasses(EXTENSION_PACKAGE);

    @Test
    void extensionDoesNotDependOnSpring() {
        // given
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.springframework..")
                .because("the Java agent extension is loaded without Spring");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void domainConfigurationDoesNotDependOnLiveConfigProperties() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage(CONFIGURATION_PACKAGE)
                .and().haveSimpleNameEndingWith("ExtensionConfig")
                .and().doNotHaveSimpleName("ExtensionConfig")
                .and().doNotHaveSimpleName("SamplingExtensionConfig")
                .should().dependOnClassesThat().haveFullyQualifiedName(CONFIG_PROPERTIES)
                .because("domain configuration must be an immutable typed snapshot");

        // when
        // then
        rule.check(productionClasses(CONFIGURATION_PACKAGE));
    }

    @Test
    void domainConfigurationDoesNotHoldLiveConfigProperties() {
        // given
        ArchRule rule = noFields()
                .that().areDeclaredInClassesThat().resideInAPackage(CONFIGURATION_PACKAGE)
                .and().areDeclaredInClassesThat().haveSimpleNameEndingWith("ExtensionConfig")
                .should().haveRawType(CONFIG_PROPERTIES)
                .because("domain configuration must not retain live ConfigProperties state");

        // when
        // then
        rule.check(productionClasses(CONFIGURATION_PACKAGE));
    }

    @Test
    void productionExtensionDoesNotDependOnSlf4j() {
        // given
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("org.slf4j..")
                .because("typed PlatformAgentLogger is the only Agent logging path");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void productionExtensionDoesNotDependOnLoggingStarterPackages() {
        // given
        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("space.br1440.platform.logging..")
                .because("Agent consumes platform-log schema at build time only");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    @Test
    void onlyBootstrapOwnerCreatesExtensionConfiguration() {
        // given
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("AutoconfigureSessionFactory")
                .should().callConstructor(ExtensionConfig.class, ConfigProperties.class)
                .because("AutoconfigureSessionFactory owns the extension configuration snapshot");

        // when
        // then
        rule.check(EXTENSION_CLASSES);
    }

    private static JavaClasses productionClasses(String packageName) {
        return new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages(packageName);
    }
}
