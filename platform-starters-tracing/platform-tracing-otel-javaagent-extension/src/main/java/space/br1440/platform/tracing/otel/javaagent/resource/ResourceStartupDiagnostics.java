package space.br1440.platform.tracing.otel.javaagent.resource;

import space.br1440.platform.tracing.api.attributes.PlatformAttributes;
import space.br1440.platform.tracing.otel.javaagent.configuration.ExtensionPropertyNames;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentEventCodes;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogLevel;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.common.util.StringUtils;

import io.opentelemetry.api.common.AttributeKey;
import io.opentelemetry.api.common.Attributes;
import lombok.experimental.UtilityClass;

@UtilityClass
public final class ResourceStartupDiagnostics {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(ResourceStartupDiagnostics.class);

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
        log.emit(AgentLogLevel.INFO, AgentEventCodes.BOOT_RESOURCE_IDENTITY, "Resource identity resolved", null,
                "service", orAbsent(summary.serviceName()),
                "environment", orAbsent(summary.environment()),
                "version", orAbsent(summary.serviceVersion()),
                "cGroup", orAbsent(summary.cGroup()),
                "policy", orAbsent(summary.policyVersion()),
                "kind", String.valueOf(summary.mode()),
                "count", String.valueOf(summary.missingCount()));
    }

    private static String orAbsent(String value) {
        return StringUtils.hasText(value) ? value : "<absent>";
    }
}
