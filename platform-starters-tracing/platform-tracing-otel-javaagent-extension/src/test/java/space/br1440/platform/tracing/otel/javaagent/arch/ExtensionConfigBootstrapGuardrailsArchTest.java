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
 * <h2>G4 — Конструктор {@link ExtensionConfig} только у владельца фазы</h2>
 * После S6 {@code ExtensionConfig} конструирует ровно один производственный класс —
 * {@code BootstrapCoordinator}, владелец фазы prepare(). Автономные
 * OTel SPI-плоскости ({@code ServiceLoader}-lifecycle) связывают только свой домен локально:
 * {@code PlatformSamplerProvider} через {@code SamplingExtensionConfig.from(ConfigProperties)},
 * {@code SafeResourceCustomizer} — прямым чтением resource-ключей.
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
     * G4: только {@code BootstrapCoordinator} (владелец фазы prepare) может
     * вызывать {@code new ExtensionConfig(ConfigProperties)}.
     * <p>
     * Ни один другой production-класс не должен конструировать {@link ExtensionConfig} —
     * factory-классы обязаны получать domain config через параметры метода или конструктора,
     * а автономные SPI-плоскости — связывать только свой домен локально. Это удерживает снапшот
     * в границах ровно одной фазы жизненного цикла и предотвращает появление нескольких
     * несинхронизированных экземпляров.
     *
     * @see <a href="docs/decisions/ADR-extensionconfig-snapshot-scope.md">ADR: snapshot scope</a>
     */
    @Test
    void только_владелец_фазы_строит_ExtensionConfig() {
        ArchRule rule = noClasses()
                .that().doNotHaveSimpleName("BootstrapCoordinator")
                .should().callConstructor(ExtensionConfig.class, ConfigProperties.class)
                .because("после r5 bootstrap refactoring ExtensionConfig — composition root ровно "
                        + "одной фазы: его строит только BootstrapCoordinator.prepare(), общий "
                        + "для обоих SPI entrypoints. Автономные SPI-плоскости связывают свой домен "
                        + "локально (SamplingExtensionConfig.from), остальные классы получают domain "
                        + "config через параметры (guardrail G4)");

        rule.check(EXTENSION_PRODUCTION_CLASSES);
    }
}
