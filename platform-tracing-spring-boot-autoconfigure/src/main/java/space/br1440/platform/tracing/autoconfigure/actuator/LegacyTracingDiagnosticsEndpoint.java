package space.br1440.platform.tracing.autoconfigure.actuator;

import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.core.env.Environment;
import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.SpanFactoryDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator-эндпоинт {@code /actuator/tracing}.
 * <p>
 * Read-model: только Spring-applied / JMX-reconciled конфигурация плюс agent-effective снимки
 * ({@link OtelEffectiveConfigSnapshot}, {@link ResourceEffectiveSnapshot}).
 */
@Endpoint(id = "tracing")
public class LegacyTracingDiagnosticsEndpoint {

    private static final String SPRING_APPLICATION_NAME = "spring.application.name";

    private final TraceOperations traceOperations;
    private final TracingProperties properties;
    private final PlatformTracingJmxClient jmxClient;
    private final SpanFactoryDiagnostics spanFactoryDiagnostics;
    private final OtelEffectiveConfigSnapshot otelEffectiveSnapshot;
    private final ResourceEffectiveSnapshot resourceEffectiveSnapshot;
    private final Environment environment;

    private SdkModeDiagnostics sdkModeDiagnostics;

    public LegacyTracingDiagnosticsEndpoint(TraceOperations traceOperations,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   SpanFactoryDiagnostics spanFactoryDiagnostics,
                                   Environment environment) {
        this(traceOperations, properties, jmxClient, null, spanFactoryDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot(), environment);
    }

    public LegacyTracingDiagnosticsEndpoint(TraceOperations traceOperations,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   SdkModeDiagnostics sdkModeDiagnostics,
                                   SpanFactoryDiagnostics spanFactoryDiagnostics,
                                   Environment environment) {
        this(traceOperations, properties, jmxClient, sdkModeDiagnostics, spanFactoryDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot(), environment);
    }

    LegacyTracingDiagnosticsEndpoint(TraceOperations traceOperations,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot,
                            SpanFactoryDiagnostics spanFactoryDiagnostics,
                            Environment environment) {
        this(traceOperations, properties, jmxClient, null, spanFactoryDiagnostics,
                otelEffectiveSnapshot, resourceEffectiveSnapshot, environment);
    }

    LegacyTracingDiagnosticsEndpoint(TraceOperations traceOperations,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            SdkModeDiagnostics sdkModeDiagnostics,
                            SpanFactoryDiagnostics spanFactoryDiagnostics,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot,
                            Environment environment) {
        this.traceOperations = traceOperations;
        this.properties = properties;
        this.jmxClient = jmxClient;
        this.spanFactoryDiagnostics = spanFactoryDiagnostics;
        this.sdkModeDiagnostics = sdkModeDiagnostics;
        this.otelEffectiveSnapshot = otelEffectiveSnapshot;
        this.resourceEffectiveSnapshot = resourceEffectiveSnapshot;
        this.environment = environment;
    }

    @ReadOperation
    public Map<String, Object> tracing() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("mode", properties.getMode().name());
        info.put("implementation", traceOperations.getClass().getName());
        info.put("spanFactory", spanFactoryDiagnostics.toActuatorMap());

        Map<String, Object> sdkInfo = new LinkedHashMap<>();
        sdkInfo.put("mode", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.mode().name()
                : properties.getMode().name());
        sdkInfo.put("configuredMode", properties.getMode().name());
        sdkInfo.put("agentDetected", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.agentDetected()
                : OtelAgentDetector.isAgentPresent());
        if (sdkModeDiagnostics != null) {
            sdkInfo.put("runtimeState", sdkModeDiagnostics.runtimeState().name());
            if (sdkModeDiagnostics.extensionDescriptor() != null) {
                sdkInfo.put("extensionVersion", sdkModeDiagnostics.extensionDescriptor().extensionVersion());
                sdkInfo.put("attestationProtocolVersion",
                        sdkModeDiagnostics.extensionDescriptor().protocolVersion());
                sdkInfo.put("extensionLifecycle", sdkModeDiagnostics.extensionDescriptor().lifecycle());
                sdkInfo.put("extensionFailureCode", sdkModeDiagnostics.extensionDescriptor().failureCode());
                sdkInfo.put("extensionCapabilities", sdkModeDiagnostics.extensionDescriptor().capabilities());
            }
        }
        info.put("sdk", sdkInfo);
        info.put("currentTraceId", traceOperations.traceContext().traceId().orElse(null));
        info.put("currentSpanId", traceOperations.traceContext().spanId().orElse(null));
        info.put("service", Map.of(
                "name", nullSafe(springApplicationName())
        ));

        // Sampling desired Spring mirrors removed; live state is Agent/JMX-owned
        // (and Category A policy is platform.tracing.policy.sampling.*).
        Map<String, Object> samplingInfo = new LinkedHashMap<>();
        samplingInfo.put("liveRatio", jmxClient.getCurrentRatio().orElse(null));
        samplingInfo.put("liveSamplerEnabled", jmxClient.isSamplerEnabled().orElse(null));
        samplingInfo.put("routeRatioCount", jmxClient.getLiveRouteRatioCount().orElse(null));
        samplingInfo.put("controlAvailable", jmxClient.isAvailable());
        samplingInfo.put("configVersion", jmxClient.getSamplingConfigVersion().orElse(null));
        samplingInfo.put("configSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        samplingInfo.put("invalidConfigCount", jmxClient.getInvalidConfigCount().orElse(null));
        samplingInfo.put("decisions", new LinkedHashMap<>(jmxClient.getSamplerDecisionCounts()));
        info.put("sampling", samplingInfo);

        info.put("export", jmxClient.getExportMetrics()
                .orElseGet(() -> Map.of("status", "not_ready")));
        info.put("requestIdHeader", Map.of(
                "enabled", properties.getRequestIdHeader().isEnabled(),
                "name", properties.getRequestIdHeader().getName()
        ));

        Map<String, Object> scrubbingInfo = new LinkedHashMap<>();
        // Category B scrubbing enabled/built-in-rules are Agent-owned
        // (javaagent.scrubbing.*); Spring actuator projects live JMX only.
        scrubbingInfo.put("liveEnabled", jmxClient.isScrubbingEnabled().orElse(null));
        scrubbingInfo.put("configVersion", jmxClient.getScrubbingConfigVersion().orElse(null));
        scrubbingInfo.put("configSource", jmxClient.getScrubbingConfigLastUpdatedSource().orElse(null));
        Map<String, Long> scrubbingMetrics = jmxClient.getScrubbingMetrics();
        scrubbingInfo.put("liveRuleCount", scrubbingMetrics.isEmpty() ? null : scrubbingMetrics.get("rules.loaded"));
        info.put("scrubbing", scrubbingInfo);

        // Validation desired Spring mirrors removed; live state is Agent/JMX-owned
        // (Category A: platform.tracing.policy.validation.*).
        Map<String, Object> validationInfo = new LinkedHashMap<>();
        validationInfo.put("liveEnabled", jmxClient.isValidationEnabled().orElse(null));
        validationInfo.put("liveStrict", jmxClient.isValidationStrict().orElse(null));
        validationInfo.put("liveStrictRuntimeAllowed", jmxClient.isValidationStrictRuntimeAllowed().orElse(null));
        validationInfo.put("configVersion", jmxClient.getValidationConfigVersion().orElse(null));
        validationInfo.put("configSource", jmxClient.getValidationConfigLastUpdatedSource().orElse(null));
        info.put("validation", validationInfo);

        info.put("otelEffective", otelEffectiveSnapshot.build());
        info.put("resourceEffective", resourceEffectiveSnapshot.build());

        Map<String, Object> processorInfo = new LinkedHashMap<>();
        processorInfo.put("errorsTotal", jmxClient.getProcessorErrorsTotal().orElse(null));
        processorInfo.put("errorsByName", jmxClient.getProcessorErrorsByName());
        info.put("processors", processorInfo);

        Map<String, Object> configInfo = new LinkedHashMap<>();
        Map<String, Long> reload = jmxClient.getConfigReloadMetrics();
        configInfo.put("updatesApplied", reload.get("updates.applied"));
        configInfo.put("updatesRejected", reload.get("updates.rejected"));
        configInfo.put("lastUpdateEpochMs", reload.get("last_update.epoch_ms"));
        configInfo.put("lastUpdatedSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        configInfo.put("auditTrail", jmxClient.getConfigAuditTrail());
        info.put("config", configInfo);

        return info;
    }

    private String springApplicationName() {
        if (environment == null) {
            return null;
        }
        return environment.getProperty(SPRING_APPLICATION_NAME);
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
