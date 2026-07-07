package space.br1440.platform.tracing.autoconfigure.actuator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.boot.actuate.endpoint.annotation.Selector;
import org.springframework.boot.actuate.endpoint.annotation.WriteOperation;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import space.br1440.platform.tracing.api.PlatformTracing;
import space.br1440.platform.tracing.autoconfigure.TracingProperties;
import space.br1440.platform.tracing.autoconfigure.diagnostics.ManualTracingDiagnostics;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.PlatformTracingJmxOperationException;
import space.br1440.platform.tracing.autoconfigure.support.OtelAgentDetector;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Actuator-эндпоинт {@code /actuator/tracing}.
 * <p>
 * Предоставляет диагностический срез текущего состояния платформенной трассировки
 * ({@link #tracing()}, {@code GET /actuator/tracing}) и динамическое управление параметрами
 * ({@code POST /actuator/tracing/{property}/{value}}).
 */
@Endpoint(id = "tracing")
public class TracingActuatorEndpoint {

    private static final Logger log = LoggerFactory.getLogger(TracingActuatorEndpoint.class);

    private final PlatformTracing platformTracing;
    private final TracingProperties properties;
    private final PlatformTracingJmxClient jmxClient;
    private final ManualTracingDiagnostics manualTracingDiagnostics;
    private final OtelEffectiveConfigSnapshot otelEffectiveSnapshot;
    private final ResourceEffectiveSnapshot resourceEffectiveSnapshot;

    private SdkModeDiagnostics sdkModeDiagnostics;

    public TracingActuatorEndpoint(PlatformTracing platformTracing,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, null, manualTracingDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot());
    }

    public TracingActuatorEndpoint(PlatformTracing platformTracing,
                                   TracingProperties properties,
                                   PlatformTracingJmxClient jmxClient,
                                   SdkModeDiagnostics sdkModeDiagnostics,
                                   ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, sdkModeDiagnostics, manualTracingDiagnostics,
                new OtelEffectiveConfigSnapshot(), new ResourceEffectiveSnapshot());
    }

    TracingActuatorEndpoint(PlatformTracing platformTracing,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot,
                            ManualTracingDiagnostics manualTracingDiagnostics) {
        this(platformTracing, properties, jmxClient, null, manualTracingDiagnostics,
                otelEffectiveSnapshot, resourceEffectiveSnapshot);
    }

    TracingActuatorEndpoint(PlatformTracing platformTracing,
                            TracingProperties properties,
                            PlatformTracingJmxClient jmxClient,
                            SdkModeDiagnostics sdkModeDiagnostics,
                            ManualTracingDiagnostics manualTracingDiagnostics,
                            OtelEffectiveConfigSnapshot otelEffectiveSnapshot,
                            ResourceEffectiveSnapshot resourceEffectiveSnapshot) {
        this.platformTracing = platformTracing;
        this.properties = properties;
        this.jmxClient = jmxClient;
        this.manualTracingDiagnostics = manualTracingDiagnostics;
        this.sdkModeDiagnostics = sdkModeDiagnostics;
        this.otelEffectiveSnapshot = otelEffectiveSnapshot;
        this.resourceEffectiveSnapshot = resourceEffectiveSnapshot;
    }

    @ReadOperation
    public Map<String, Object> tracing() {
        Map<String, Object> info = new LinkedHashMap<>();
        info.put("enabled", properties.isEnabled());
        info.put("implementation", platformTracing.getClass().getName());
        info.put("manualTracing", manualTracingDiagnostics.toActuatorMap());

        Map<String, Object> sdkInfo = new LinkedHashMap<>();
        sdkInfo.put("mode", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.mode().name()
                : properties.getSdk().getMode().name());
        sdkInfo.put("configuredMode", properties.getSdk().getMode().name());
        sdkInfo.put("agentDetected", sdkModeDiagnostics != null
                ? sdkModeDiagnostics.agentDetected()
                : OtelAgentDetector.isAgentPresent());
        info.put("sdk", sdkInfo);
        info.put("currentTraceId", platformTracing.traceContext().traceId().orElse(null));
        info.put("currentSpanId", platformTracing.traceContext().spanId().orElse(null));
        info.put("service", Map.of(
                "name", nullSafe(properties.getService().getName()),
                "version", nullSafe(properties.getService().getVersion()),
                "environment", nullSafe(properties.getService().getEnvironment()),
                "cGroup", nullSafe(properties.getService().getCGroup())
        ));

        Map<String, Object> samplingInfo = new LinkedHashMap<>();
        samplingInfo.put("enabled", properties.getSampling().isEnabled());
        samplingInfo.put("ratio", properties.getSampling().getRatio());
        samplingInfo.put("routeRatios", properties.getSampling().getRouteRatios());
        samplingInfo.put("liveRatio", jmxClient.getCurrentRatio().orElse(null));
        samplingInfo.put("samplerEnabled", jmxClient.isSamplerEnabled().orElse(null));
        samplingInfo.put("liveSamplerEnabled", jmxClient.isSamplerEnabled().orElse(null));
        samplingInfo.put("liveDropPaths", jmxClient.getLiveDropPathPrefixes().orElse(null));
        samplingInfo.put("liveForceRecordHeaderValues", jmxClient.getLiveForceRecordValues().orElse(null));
        samplingInfo.put("liveRouteRatios", jmxClient.getLiveRouteRatios().orElse(null));
        samplingInfo.put("controlAvailable", jmxClient.isAvailable());
        samplingInfo.put("forceRecordHeader", properties.getSampling().getForceRecordHeader());
        samplingInfo.put("forceRecordHeaderValues", properties.getSampling().getForceRecordHeaderValues());
        samplingInfo.put("qaForceHeader", properties.getSampling().getQaForceHeader());
        samplingInfo.put("dropPaths", properties.getSampling().getDropPaths());
        samplingInfo.put("configVersion", jmxClient.getSamplingConfigVersion().orElse(null));
        samplingInfo.put("configSource", jmxClient.getSamplingConfigLastUpdatedSource().orElse(null));
        samplingInfo.put("invalidConfigCount", jmxClient.getInvalidConfigCount().orElse(null));

        Map<String, Object> decisions = new LinkedHashMap<>(jmxClient.getSamplerDecisionCounts());
        samplingInfo.put("decisions", decisions);
        info.put("sampling", samplingInfo);

        info.put("limits", Map.of(
                "maxAttributes", properties.getLimits().getMaxAttributes(),
                "maxAttributeValueLength", properties.getLimits().getMaxAttributeValueLength(),
                "maxEvents", properties.getLimits().getMaxEvents(),
                "spanTimeout", properties.getLimits().getSpanTimeout().toString(),
                "traceTimeout", properties.getLimits().getTraceTimeout().toString()
        ));
        info.put("queue", Map.of(
                "maxSize", properties.getQueue().getMaxSize(),
                "policy", properties.getQueue().getPolicy().name(),
                "exportBatchSize", properties.getQueue().getExportBatchSize(),
                "exportTimeout", properties.getQueue().getExportTimeout().toString()
        ));
        info.put("exporter", Map.of(
                "otlpEndpoint", properties.getExporter().getOtlp().getEndpoint(),
                "retryEnabled", properties.getExporter().getOtlp().getRetry().isEnabled()
        ));
        info.put("export", jmxClient.getExportMetrics()
                .orElseGet(() -> Map.of("status", "not_ready")));
        info.put("response", Map.of(
                "exposeRequestIdHeader", properties.getResponse().isExposeRequestIdHeader(),
                "headerName", properties.getResponse().getHeaderName()
        ));
        info.put("enriching", Map.of(
                "enabled", properties.getEnriching().isEnabled(),
                "remoteServicePriority", properties.getEnriching().getRemoteServicePriority()
        ));

        Map<String, Object> scrubbingInfo = new LinkedHashMap<>();
        scrubbingInfo.put("enabled", properties.getScrubbing().isEnabled());
        scrubbingInfo.put("builtInRules", properties.getScrubbing().getBuiltInRules());
        scrubbingInfo.put("liveEnabled", jmxClient.isScrubbingEnabled().orElse(null));
        scrubbingInfo.put("configVersion", jmxClient.getScrubbingConfigVersion().orElse(null));
        scrubbingInfo.put("configSource", jmxClient.getScrubbingConfigLastUpdatedSource().orElse(null));
        Map<String, Long> scrubbingMetrics = jmxClient.getScrubbingMetrics();
        scrubbingInfo.put("liveRuleCount", scrubbingMetrics.isEmpty() ? null : scrubbingMetrics.get("rules.loaded"));
        scrubbingInfo.put("customRulesSource", "otel-agent-spi");
        scrubbingInfo.put("customRulesVisible", false);
        scrubbingInfo.put("note", "SPI-реализации SensitiveDataRule грузятся classloader'ом OTel Agent и в actuator не видны");
        scrubbingInfo.put("rulesConfig", nullSafe(properties.getScrubbing().getRulesConfig()));
        info.put("scrubbing", scrubbingInfo);

        Map<String, Object> validationInfo = new LinkedHashMap<>();
        validationInfo.put("enabled", properties.getValidation().isEnabled());
        validationInfo.put("strict", properties.getValidation().isStrict());
        validationInfo.put("strictRuntimeAllowed", properties.getValidation().isStrictRuntimeAllowed());
        validationInfo.put("liveEnabled", jmxClient.isValidationEnabled().orElse(null));
        validationInfo.put("liveStrict", jmxClient.isValidationStrict().orElse(null));
        validationInfo.put("liveStrictRuntimeAllowed", jmxClient.isValidationStrictRuntimeAllowed().orElse(null));
        validationInfo.put("configVersion", jmxClient.getValidationConfigVersion().orElse(null));
        validationInfo.put("configSource", jmxClient.getValidationConfigLastUpdatedSource().orElse(null));
        validationInfo.put("strictRuntimeAllowedNote",
                "configured strictRuntimeAllowed is Spring input; liveStrictRuntimeAllowed is agent startup "
                        + "enforcement from platform.tracing.validation.strict-runtime-allowed");
        info.put("validation", validationInfo);

        info.put("watchdog", Map.of(
                "enabled", properties.getWatchdog().isEnabled(),
                "scanInterval", properties.getWatchdog().getScanInterval().toString(),
                "spanTimeout", properties.getLimits().getSpanTimeout().toString(),
                "traceTimeout", properties.getLimits().getTraceTimeout().toString()
        ));
        info.put("otelEffective", otelEffectiveSnapshot.build());
        info.put("otelEnvHints", OtelEnvHintsBuilder.from(properties));
        info.put("resourceEffective", resourceEffectiveSnapshot.build());

        Map<String, Object> resourceSpringConfig = new LinkedHashMap<>();
        resourceSpringConfig.put("serviceName", nullSafe(properties.getService().getName()));
        resourceSpringConfig.put("serviceVersion", nullSafe(properties.getService().getVersion()));
        resourceSpringConfig.put("environment", nullSafe(properties.getService().getEnvironment()));
        resourceSpringConfig.put("cGroup", nullSafe(properties.getService().getCGroup()));
        resourceSpringConfig.put("policyVersion", nullSafe(properties.getResource().getPolicyVersion()));
        resourceSpringConfig.put("normalizeEnvironment", properties.getResource().isNormalizeEnvironment());
        resourceSpringConfig.put("validationMode", properties.getResource().getValidationMode());
        resourceSpringConfig.put("detectContainerId", properties.getResource().isDetectContainerId());
        info.put("resourceSpringConfig", resourceSpringConfig);

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

        info.put("actuator", Map.of(
                "mutationEnabled", properties.getActuator().isMutationEnabled()
        ));
        return info;
    }

    @WriteOperation
    public Map<String, Object> updateTracing(@Selector String property, @Selector String value) {
        assertMutationAllowed();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("property", property);
        result.put("requestedValue", value);

        switch (property) {
            case "enabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                boolean previous = properties.isEnabled();
                properties.setEnabled(enabled);
                log.info("Платформенная трассировка {} -> {} через actuator endpoint", previous, enabled);
                result.put("previousValue", previous);
                result.put("appliedValue", enabled);
            }
            case "samplerEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setSamplerEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                result.put("status", "updated");
                result.put("effectiveSamplerEnabled", enabled);
            }
            case "samplingRatio" -> {
                double ratio;
                try {
                    ratio = Double.parseDouble(value);
                } catch (NumberFormatException e) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "samplingRatio must be a number, got: " + value,
                            e);
                }
                if (ratio < 0.0 || ratio > 1.0) {
                    throw new ResponseStatusException(
                            HttpStatus.BAD_REQUEST,
                            "samplingRatio must be in [0.0, 1.0], got: " + ratio);
                }
                Double previousLive = jmxClient.getCurrentRatio().orElse(null);
                try {
                    jmxClient.setRatio(ratio);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                double previousProperties = properties.getSampling().getRatio();
                properties.getSampling().setRatio(ratio);
                log.info("samplingRatio {} -> {} через actuator endpoint (JMX)",
                        previousLive != null ? previousLive : previousProperties, ratio);
                result.put("previousLiveValue", previousLive);
                result.put("previousConfiguredValue", previousProperties);
                result.put("appliedValue", ratio);
            }
            case "exportEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setExportEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getExporter().setEnabled(enabled);
                log.info("Export-gate -> {} через actuator endpoint (JMX)", enabled);
                result.put("status", "updated");
                result.put("appliedValue", enabled);
            }
            case "propagationEnabled" -> {
                boolean enabled = Boolean.parseBoolean(value);
                try {
                    jmxClient.setPropagationEnabled(enabled);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getPropagation().setEnabled(enabled);
                log.info("Platform propagation -> {} через actuator endpoint (JMX)", enabled);
                result.put("status", "updated");
                result.put("appliedValue", enabled);
            }
            case "logLevel" -> {
                try {
                    jmxClient.setPlatformLogLevel(value);
                } catch (PlatformTracingJmxOperationException e) {
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, e.getMessage(), e);
                }
                properties.getDiagnostics().setLogLevel(value);
                log.info("Platform log level -> {} через actuator endpoint (JMX)", value);
                result.put("status", "updated");
                result.put("appliedValue", value);
            }
            default -> throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST,
                    "Unsupported tracing property: " + property
                            + ". Supported: enabled, samplerEnabled, samplingRatio, exportEnabled, propagationEnabled, logLevel");
        }
        return result;
    }

    private void assertMutationAllowed() {
        if (!properties.getActuator().isMutationEnabled()) {
            throw new ResponseStatusException(
                    HttpStatus.FORBIDDEN,
                    "Actuator tracing mutation is disabled. Set platform.tracing.actuator.mutation-enabled=true "
                            + "for local/dev/debug/test/pre-prod only. Direct JMX access is not affected.");
        }
    }

    private static String nullSafe(String value) {
        return value == null ? "" : value;
    }
}
