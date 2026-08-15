package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@UtilityClass
public final class ResourceStartupDiagnostics {

    private static final AttributeKey<String> SERVICE_NAME = AttributeKey.stringKey(PlatformAttributes.SERVICE_NAME);
    private static final AttributeKey<String> SERVICE_VERSION = AttributeKey.stringKey(PlatformAttributes.SERVICE_VERSION);
    private static final AttributeKey<String> ENVIRONMENT = AttributeKey.stringKey(PlatformAttributes.PLATFORM_ENVIRONMENT);
    private static final AttributeKey<String> C_GROUP = AttributeKey.stringKey(PlatformAttributes.PLATFORM_C_GROUP);
    private static final AttributeKey<String> POLICY_VERSION = AttributeKey.stringKey(ExtensionPropertyNames.RESOURCE_POLICY_VERSION_ATTR);

    public record Summary(String serviceName, String serviceVersion, String environment, String cGroup,
                          String policyVersion, ResourceValidationMode mode, int missingCount) {
    }

    public static Summary from(Attributes resolved, int missingCount, ResourceValidationMode mode) {
        return new Summary(
                resolved.get(SERVICE_NAME),
                resolved.get(SERVICE_VERSION),
                resolved.get(ENVIRONMENT),
                resolved.get(C_GROUP),
                resolved.get(POLICY_VERSION),
                mode,
                missingCount
        );
    }

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
        return StringUtils.isNullOrBlank(value) ? "<absent>" : value;
    }
}
