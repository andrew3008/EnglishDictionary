package space.br1440.platform.tracing.otel.extension.arch;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * Архитектурный инвариант Фазы 9: resource-ключи идентичности не должны писаться span-процессорами.
 * <p>
 * Resource описывает сущность (сервис/хост/контейнер) и живёт на уровне {@code Resource}
 * ({@code PlatformResourceProvider}), а не операции. Дублирование этих ключей как span-атрибутов
 * нарушает OTel-семантику, раздувает лимиты и расходится между traces/metrics/logs. Правило ловит
 * регресс: ни один класс в пакете {@code ..processor..} не должен обращаться к константам
 * resource-ключей в {@link PlatformAttributes} (что косвенно означало бы их запись в span).
 */
class ResourceKeysNotInSpanProcessorsArchTest {

    @Test
    void процессоры_не_обращаются_к_resource_ключам() {
        JavaClasses processors = new ClassFileImporter()
                .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
                .importPackages("space.br1440.platform.tracing.otel.extension.processor");

        ArchRule rule = noClasses()
                .should().accessField(PlatformAttributes.class, "SERVICE_NAME")
                .orShould().accessField(PlatformAttributes.class, "SERVICE_VERSION")
                .orShould().accessField(PlatformAttributes.class, "CONTAINER_ID")
                .orShould().accessField(PlatformAttributes.class, "PLATFORM_HOST")
                .orShould().accessField(PlatformAttributes.class, "PLATFORM_ENVIRONMENT")
                .orShould().accessField(PlatformAttributes.class, "PLATFORM_C_GROUP")
                .orShould().accessField(PlatformAttributes.class, "PLATFORM_ID")
                .because("resource-ключи идентичности принадлежат уровню Resource "
                        + "(PlatformResourceProvider), а не span-процессорам");

        rule.check(processors);
    }
}
