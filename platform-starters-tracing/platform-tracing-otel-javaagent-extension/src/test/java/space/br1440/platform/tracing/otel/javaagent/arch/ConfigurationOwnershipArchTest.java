package space.br1440.platform.tracing.otel.javaagent.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noMethods;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Map;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.configuration.PlatformTracingDefaultsProvider;

/**
 * S7 architectural gates for configuration ownership after the S1–S6 cleanup.
 *
 * <ul>
 *   <li>G-S7-1 — single reader of {@link ExtensionPropertyNames} keys</li>
 *   <li>G-S7-2 — single default owner (no unconditional {@code supply()} write of platform keys)</li>
 *   <li>G-S7-3 — deleted constant wrappers stay deleted; {@code configuration.spi/enums} stay gone</li>
 *   <li>G-S7-4 — secret-shaped fields are not raw {@code String}</li>
 *   <li>G-S7-5 — no live {@link ConfigProperties} in long-lived runtime state</li>
 *   <li>G-S7-6 — control plane after bootstrap does not depend on startup configuration</li>
 * </ul>
 */
class ConfigurationOwnershipArchTest {

    private static final String ROOT = "space.br1440.platform.tracing.otel.javaagent";
    private static final String CONFIGURATION = ROOT + ".configuration";
    private static final String RESOURCE = ROOT + ".resource";
    private static final String EXTENSION_PROPERTY_NAMES = CONFIGURATION + ".ExtensionPropertyNames";

    private static final JavaClasses EXTENSION_PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages(ROOT);

    // -----------------------------------------------------------------------------------------
    // G-S7-1 — single reader of ExtensionPropertyNames
    // -----------------------------------------------------------------------------------------

    /**
     * Keys declared in {@link ExtensionPropertyNames} may be referenced only by:
     * <ul>
     *   <li>the {@code configuration} package (domain facades, reader, defaults provider, paths);</li>
     *   <li>the {@code resource} package (SPI-local resource key owner);</li>
     *   <li>{@code BootstrapCoordinator} — fail-closed scrubbing message text only,
     *       not a second domain reader;</li>
     *   <li>{@code ConfigurationFingerprint} — deterministic content hash of the same
     *       key set, not a domain consumer.</li>
     * </ul>
     * Factories, processors, samplers, control, propagation and scrubbing must not become
     * second readers of these keys.
     */
    @Test
    void extensionPropertyNames_referenced_only_by_documented_owners() {
        ArchRule rule = noClasses()
                .that().resideOutsideOfPackages(CONFIGURATION, RESOURCE)
                .and().doNotHaveSimpleName("BootstrapCoordinator")
                .and().doNotHaveSimpleName("ConfigurationFingerprint")
                .should().dependOnClassesThat().haveFullyQualifiedName(EXTENSION_PROPERTY_NAMES)
                .because("ExtensionPropertyNames keys have a single reader per owner "
                        + "(configuration facades / resource SPI / paths helper); "
                        + "bootstrap coordinator/fingerprint may reference keys for fail-closed "
                        + "messages and content hashing only; other packages must not become "
                        + "second readers (S7 G-S7-1 / r5 S6 rename)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    // -----------------------------------------------------------------------------------------
    // G-S7-2 — single default owner
    // -----------------------------------------------------------------------------------------

    /**
     * {@link PlatformTracingDefaultsProvider#supply()} may inject {@code otel.*} SDK defaults and
     * env-present platform bridges, but must never unconditionally write a key owned by
     * {@link ExtensionPropertyNames} / {@code ExtensionDefaults}.
     */
    @Test
    void supply_without_env_does_not_write_extension_property_names_platform_keys() throws Exception {
        Map<String, String> supplied = new PlatformTracingDefaultsProvider(name -> null).supply();

        for (Field field : ExtensionPropertyNames.class.getFields()) {
            if (!Modifier.isStatic(field.getModifiers()) || field.getType() != String.class) {
                continue;
            }
            String key = (String) field.get(null);
            if (key.startsWith("otel.")) {
                // otel.javaagent.extensions is a name constant, not a platform default owned by
                // ExtensionDefaults; supply() must not invent a value for it either.
                assertThat(supplied)
                        .as("supply() must not invent a value for %s", key)
                        .doesNotContainKey(key);
                continue;
            }
            assertThat(supplied)
                    .as("platform key %s is owned by ExtensionDefaults / domain facade; "
                            + "supply() may write it only via env fallback (S7 G-S7-2)", key)
                    .doesNotContainKey(key);
        }
    }

    // -----------------------------------------------------------------------------------------
    // G-S7-3 — no constant wrappers / deleted subpackages
    // -----------------------------------------------------------------------------------------

    @Test
    void deleted_constant_wrappers_and_subpackages_do_not_return() {
        assertThatThrownBy(() -> Class.forName(
                CONFIGURATION + ".AutoConfigurationCustomizerOrdering"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                CONFIGURATION + ".DropOldestExportProcessorDefaults"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                CONFIGURATION + ".spi.OtelSdkDefaults"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                CONFIGURATION + ".spi.PlatformTracingDefaultsProvider"))
                .isInstanceOf(ClassNotFoundException.class);
        assertThatThrownBy(() -> Class.forName(
                CONFIGURATION + ".enums.QueueOverflowPolicy"))
                .isInstanceOf(ClassNotFoundException.class);
    }

    @Test
    void configuration_spi_and_enums_subpackages_stay_absent() {
        ArchRule rule = noClasses()
                .should().resideInAnyPackage(CONFIGURATION + ".spi..", CONFIGURATION + ".enums..")
                .because("S5 eliminated configuration.spi and configuration.enums; "
                        + "reintroducing them recreates visibility inflation (S7 G-S7-3)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    // -----------------------------------------------------------------------------------------
    // G-S7-4 — secret-shaped fields are not raw String
    // -----------------------------------------------------------------------------------------

    @Test
    void secret_shaped_fields_are_not_raw_string() {
        // Instance fields only: static String constants like ExtensionPropertyNames.SCRUBBING_HMAC_KEY
        // are property *names*, not secret material.
        ArchRule rule = noFields()
                .that().haveNameMatching("(?i).*(hmac|secret|password|token|credential).*")
                .and().areNotStatic()
                .and().areDeclaredInClassesThat().resideInAPackage(CONFIGURATION)
                .should().haveRawType(String.class)
                .because("secret-shaped configuration fields must use MaskedSecret (or equivalent), "
                        + "not raw String (S7 G-S7-4)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void secret_shaped_public_accessors_do_not_return_raw_string() {
        ArchRule rule = noMethods()
                .that().haveNameMatching("(?i).*(hmacKey|hmac|secret|password|token|credential).*")
                .and().arePublic()
                .and().areDeclaredInClassesThat().resideInAPackage(CONFIGURATION)
                .should().haveRawReturnType(String.class)
                .because("public accessors must not expose secret material as String (S7 G-S7-4)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    // -----------------------------------------------------------------------------------------
    // G-S7-5 — no ConfigProperties in long-lived runtime state
    // -----------------------------------------------------------------------------------------

    @Test
    void long_lived_runtime_types_do_not_hold_config_properties() {
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

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    // -----------------------------------------------------------------------------------------
    // G-S7-6 — startup / runtime boundary
    // -----------------------------------------------------------------------------------------

    /**
     * After {@code TracingSettingsSource.fromStartup}, the control plane owns accepted settings.
     * Nested control packages must not reach back into startup configuration types.
     * The sole bootstrap bridge is {@code TracingSettingsSource} in the control root package.
     */
    @Test
    void control_nested_packages_do_not_depend_on_configuration() {
        ArchRule rule = noClasses()
                .that().resideInAnyPackage(
                        ROOT + ".control.settings..",
                        ROOT + ".control.jmx..")
                .should().dependOnClassesThat().resideInAPackage(CONFIGURATION + "..")
                .because("accepted settings and JMX adapters must not reference startup "
                        + "ExtensionConfig after bootstrap (S7 G-S7-6)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    @Test
    void control_root_only_tracing_settings_source_depends_on_configuration() {
        ArchRule rule = noClasses()
                .that().resideInAPackage(ROOT + ".control")
                .and().doNotHaveSimpleName("TracingSettingsSource")
                .should().dependOnClassesThat().resideInAPackage(CONFIGURATION + "..")
                .because("TracingSettingsSource is the sole bootstrap bridge from ExtensionConfig "
                        + "into the control plane (S7 G-S7-6)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }
}
