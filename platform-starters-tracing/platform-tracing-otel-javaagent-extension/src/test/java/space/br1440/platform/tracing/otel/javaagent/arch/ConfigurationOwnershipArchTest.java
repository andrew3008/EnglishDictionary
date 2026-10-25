package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;

import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;

class ConfigurationOwnershipArchTest {

    private static final String ROOT = "space.br1440.platform.tracing.otel.javaagent";
    private static final String CONFIGURATION = ROOT + ".configuration";
    private static final String RESOURCE = ROOT + ".resource";
    private static final String EXTENSION_PROPERTY_NAMES = CONFIGURATION + ".ExtensionPropertyNames";

    private static final JavaClasses EXTENSION_PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    @Test
    void extensionPropertyNames_referenced_only_by_documented_owners() {
        // given
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(CONFIGURATION, RESOURCE)
                .and().doNotHaveSimpleName("AutoconfigureSessionFactory")
                .should().dependOnClassesThat().haveFullyQualifiedName(EXTENSION_PROPERTY_NAMES)
                .because("ExtensionPropertyNames keys have a single reader per owner "
                        + "(configuration facades / resource SPI / paths helper); "
                        + "autoconfigure session factory may reference keys for fail-closed "
                        + "messages only; other packages must not become second readers");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void supply_without_env_does_not_write_extension_property_names_platform_keys() throws Exception {
        // given
        Map<String, String> supplied = new PlatformTracingDefaultsProvider().supply();

        // when/then
        for (Field field : ExtensionPropertyNames.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }

            String key = (String) field.get(null);
            if (key.startsWith("otel.")) {
                assertThat(supplied)
                        .as("supply() must not invent a value for %s", key)
                        .doesNotContainKey(key);
                continue;
            }

            assertThat(supplied)
                    .as("platform key %s is owned by ExtensionDefaults / domain facade; "
                            + "supply() does not write it", key)
                    .doesNotContainKey(key);
        }
    }

    @Test
    void secret_shaped_fields_are_not_raw_string() {
        // given
        ArchRule rule = noFields()
                .that().haveNameMatching("(?i).*(hmac|secret|password|token|credential).*")
                .and().areNotStatic()
                .and().areDeclaredInClassesThat().resideInAPackage(CONFIGURATION)
                .should().haveRawType(String.class)
                .because("secret-shaped configuration fields must use MaskedSecret (or equivalent), "
                        + "not raw String (S7 G-S7-4)");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void secret_shaped_public_accessors_do_not_return_raw_string() {
        // given
        ArchRule rule = noMethods()
                .that().haveNameMatching("(?i).*(hmacKey|hmac|secret|password|token|credential).*")
                .and().arePublic()
                .and().areDeclaredInClassesThat().resideInAPackage(CONFIGURATION)
                .should().haveRawReturnType(String.class)
                .because("public accessors must not expose secret material as String (S7 G-S7-4)");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void long_lived_runtime_types_do_not_hold_config_properties() {
        // given
        ArchRule rule = noFields()
                .that().haveRawType(ConfigProperties.class)
                .should().beDeclaredInClassesThat()
                .resideInAnyPackage(
                        ROOT + ".processor..",
                        ROOT + ".sampler..",
                        ROOT + ".factory..",
                        ROOT + ".resource..",
                        ROOT + ".exporter..",
                        ROOT + ".jmx..",
                        ROOT + ".control..",
                        ROOT + ".propagation..",
                        ROOT + ".scrubbing..")
                .because("ConfigProperties is a startup binding source; long-lived processors, "
                        + "samplers and exporters must not retain it (S7 G-S7-5). "
                        + "ExtensionConfigReader in configuration is the only allowed holder.");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void control_nested_packages_do_not_depend_on_configuration() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        ROOT + ".control.settings..",
                        ROOT + ".control.jmx..")
                .should().dependOnClassesThat().resideInAPackage(CONFIGURATION + "..")
                .because("accepted settings and JMX adapters must not reference startup "
                        + "ExtensionConfig after bootstrap (S7 G-S7-6)");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void control_root_only_tracing_settings_source_depends_on_configuration() {
        // given
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT + ".control")
                .and().doNotHaveSimpleName("TracingSettingsSource")
                .should().dependOnClassesThat().resideInAPackage(CONFIGURATION + "..")
                .because("TracingSettingsSource is the sole bootstrap bridge from ExtensionConfig "
                        + "into the control plane (S7 G-S7-6)");

        // when/then
        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }
}
