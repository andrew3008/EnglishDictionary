package space.br1440.platform.tracing.otel.javaagent.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import io.opentelemetry.sdk.autoconfigure.spi.ConfigProperties;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionConfig;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-6 architecture guardrails for ExtensionConfig A3' refactoring.
 *
 * <h2>G3 — Нет descriptor registry / codegen framework</h2>
 * Запрет класс-имён, характерных для запрещённых архитектурных паттернов
 * (descriptor registry, codegen, generated metadata, PropertyKey-фреймворк).
 *
 * <h2>G4 — Конструктор {@link ExtensionConfig} только в разрешённых местах</h2>
 * {@code ExtensionConfig} конструируется ровно двумя разрешёнными производственными классами:
 * <ol>
 *   <li>{@code PlatformAutoConfigurationCustomizer} — bootstrap owner, единственная
 *       точка создания в customizer/factory chain;</li>
 *   <li>{@code PlatformSamplerProvider} — named OTel SPI exception ({@code ServiceLoader}
 *       lifecycle, аналог PR-3R RESOURCE_R0_SPI_EXCEPTION pattern).</li>
 * </ol>
 * Все остальные factory-классы получают domain config через параметры (A3' PR-2..PR-5).
 *
 * @see <a href="docs/architecture/extensionconfig-refactoring-final-architecture.md">Final Architecture</a>
 */
class ExtensionConfigBootstrapGuardrailsArchTest {

    private static final JavaClasses EXTENSION_PRODUCTION_CLASSES =
            new ClassFileImporter()
                    .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                    .importPackages("space.br1440.platform.tracing.otel.javaagent");

    // -----------------------------------------------------------------------------------------
    // G3 — Нет descriptor registry / codegen framework
    // -----------------------------------------------------------------------------------------

    /**
     * G3a: Никакой production-класс в otel-extension не должен иметь имя с суффиксом
     * {@code PropertyKey} — это признак запрещённого descriptor registry или {@code PropertyKey<T>}
     * фреймворка (A3' guardrail G3).
     */
    @Test
    void нет_ProductionKey_классов_в_otel_extension() {
        ArchRule rule = noClasses()
                .should().haveSimpleNameEndingWith("PropertyKey")
                .because("descriptor registry / PropertyKey<T> framework запрещён A3' guardrail G3; "
                        + "используйте immutable domain config классы (ExtensionConfig A3', PR-2)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    /**
     * G3b: Никакой production-класс в otel-extension не должен иметь имя с суффиксом
     * {@code PropertyRegistry} — это признак registry-фреймворка, запрещённого A3'.
     */
    @Test
    void нет_PropertyRegistry_классов_в_otel_extension() {
        ArchRule rule = noClasses()
                .should().haveSimpleNameEndingWith("PropertyRegistry")
                .because("descriptor registry / PropertyRegistry framework запрещён A3' guardrail G3; "
                        + "используйте immutable domain config классы (ExtensionConfig A3', PR-2)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }

    // -----------------------------------------------------------------------------------------
    // G4 — Конструктор ExtensionConfig только в разрешённых местах
    // -----------------------------------------------------------------------------------------

    /**
     * G4: Только {@code PlatformAutoConfigurationCustomizer} (bootstrap owner) и
     * {@code PlatformSamplerProvider} (named OTel SPI exception) могут вызывать
     * {@code new ExtensionConfig(ConfigProperties)}.
     * <p>
     * Ни один другой production-класс не должен конструировать {@link ExtensionConfig} —
     * factory-классы обязаны получать domain config через параметры метода или конструктора.
     * Это предотвращает появление нескольких несинхронизированных экземпляров ExtensionConfig
     * в bootstrap-цепочке.
     */
    @Test
    void только_разрешённые_классы_строят_ExtensionConfig() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("PlatformAutoConfigurationCustomizer")
                .and().doNotHaveSimpleName("PlatformSamplerProvider")
                .should().callConstructor(ExtensionConfig.class, ConfigProperties.class)
                .because("ExtensionConfig конструируется только bootstrap owner'ом "
                        + "(PlatformAutoConfigurationCustomizer) и named SPI exception "
                        + "(PlatformSamplerProvider). Все остальные factory-классы должны "
                        + "получать domain config через параметры (A3' PR-2..PR-5 guardrail G4)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }
}
