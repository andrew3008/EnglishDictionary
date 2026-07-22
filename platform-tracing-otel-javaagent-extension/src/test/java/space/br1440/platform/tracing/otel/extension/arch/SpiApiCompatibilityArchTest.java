package space.br1440.platform.tracing.otel.extension.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * PR-1 API Compatibility Guard: публичный SPI-контракт scrubbing'а должен быть полностью
 * платформенным и не зависеть от {@code io.opentelemetry.*}.
 * <p>
 * Это прямой урок инцидента с {@code AbstractMethodError}: когда сигнатура SPI зависела от
 * {@code AttributeKey}, shading OTel Java Agent ломал контракт между расширением и кастомными
 * правилами. Запрет {@code io.opentelemetry.*} в пакете {@code ..api.spi..} предотвращает регресс.
 */
class SpiApiCompatibilityArchTest {

    @Test
    void spi_контракт_не_зависит_от_opentelemetry() {
        JavaClasses spiClasses = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.tracing.api.spi");

        ArchRule rule = noClasses()
                .should().dependOnClassesThat().resideInAnyPackage("io.opentelemetry..")
                .because("публичный SPI scrubbing'а обязан быть platform-owned: зависимость от "
                        + "shaded/unshaded io.opentelemetry.* приводит к AbstractMethodError в "
                        + "рантайме OTel Java Agent");

        rule.check(spiClasses);
    }
}
