package space.br1440.platform.tracing.otel.extension.resource;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.extension.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.extension.utils.Strings;

/**
 * Однострочная стартовая диагностика собранной resource-идентичности (INFO).
 * <p>
 * Назначение — оператор сразу видит effective identity при старте сервиса, не дёргая actuator.
 * Соответствует индустриальной практике (Honeycomb/Observability Engineering): печатать
 * итоговый resource при инициализации.
 */
public final class ResourceStartupDiagnostics {

    private static final Logger log = LoggerFactory.getLogger(ResourceStartupDiagnostics.class);

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);
    private static final AttributeKey<String> POLICY_VERSION =
            AttributeKey.stringKey(ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR);

    private ResourceStartupDiagnostics() {
        // utility-класс
    }

    /** Сводка для лога. {@code null}-поля печатаются как {@code "<absent>"}. */
    public record Summary(String serviceName, String serviceVersion, String environment, String cGroup,
                          String policyVersion, ResourceValidationMode mode, int missingCount) {
    }

    /**
     * Строит {@link Summary} из собранных платформой атрибутов.
     *
     * @param resolved     дополненные платформой атрибуты
     * @param missingCount число отсутствующих required-ключей (из {@link ResourceValidationDiagnostics})
     * @param mode         режим валидации
     */
    public static Summary from(Attributes resolved, int missingCount, ResourceValidationMode mode) {
        return new Summary(
                resolved.get(SERVICE_NAME),
                resolved.get(SERVICE_VERSION),
                resolved.get(ENVIRONMENT),
                resolved.get(C_GROUP),
                resolved.get(POLICY_VERSION),
                mode,
                missingCount);
    }

    /** Печатает одну INFO-строку с effective identity. */
    public static void emit(Summary summary) {
        log.info("Platform resource identity: service={}, version={}, env={}, c_group={}, policy={}, mode={}, missing={}",
                orAbsent(summary.serviceName()),
                orAbsent(summary.serviceVersion()),
                orAbsent(summary.environment()),
                orAbsent(summary.cGroup()),
                orAbsent(summary.policyVersion()),
                summary.mode(),
                summary.missingCount());
    }

    private static String orAbsent(String value) {
        return Strings.isBlank(value) ? "<absent>" : value;
    }
}
