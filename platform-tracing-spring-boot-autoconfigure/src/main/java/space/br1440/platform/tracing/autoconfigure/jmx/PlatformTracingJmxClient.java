package space.br1440.platform.tracing.autoconfigure.jmx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.Collections;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Spring-side JMX-клиент шести доменных MBean платформенной плоскости управления трассировкой.
 * <p>
 * Заменяет {@code SamplingControlClient} (удалён). Знает ObjectName всех шести доменов;
 * выполняет только читающие вызовы и деградирует грациозно
 * (возвращает {@link Optional#empty()} / пустые коллекции / сентинели), если MBean домена
 * недоступен.
 */
public class PlatformTracingJmxClient {

    private static final Logger log = LoggerFactory.getLogger(PlatformTracingJmxClient.class);

    private final MBeanServer server;

    public PlatformTracingJmxClient() {
        this(ManagementFactory.getPlatformMBeanServer());
    }

    public PlatformTracingJmxClient(MBeanServer server) {
        this.server = server;
    }

    // -- Availability ----------------------------------------------------------

    /**
     * @return {@code true}, если все шесть доменных MBean зарегистрированы.
     */
    public boolean allMBeansAvailable() {
        return server.isRegistered(PlatformTracingJmxObjectNames.SAMPLING)
                && server.isRegistered(PlatformTracingJmxObjectNames.SCRUBBING)
                && server.isRegistered(PlatformTracingJmxObjectNames.VALIDATION)
                && server.isRegistered(PlatformTracingJmxObjectNames.EXPORT)
                && server.isRegistered(PlatformTracingJmxObjectNames.PROCESSOR_METRICS)
                && server.isRegistered(PlatformTracingJmxObjectNames.DIAGNOSTICS);
    }

    /**
     * Convenience alias. Эквивалентен {@link #allMBeansAvailable()}.
     */
    public boolean isAvailable() {
        return allMBeansAvailable();
    }

    /**
     * @return статус каждого домена (зарегистрирован ли его MBean).
     */
    public Map<TracingControlDomain, Boolean> getMBeansStatus() {
        Map<TracingControlDomain, Boolean> status = new EnumMap<>(TracingControlDomain.class);
        status.put(TracingControlDomain.SAMPLING, server.isRegistered(PlatformTracingJmxObjectNames.SAMPLING));
        status.put(TracingControlDomain.SCRUBBING, server.isRegistered(PlatformTracingJmxObjectNames.SCRUBBING));
        status.put(TracingControlDomain.VALIDATION, server.isRegistered(PlatformTracingJmxObjectNames.VALIDATION));
        status.put(TracingControlDomain.EXPORT, server.isRegistered(PlatformTracingJmxObjectNames.EXPORT));
        status.put(TracingControlDomain.PROCESSOR_METRICS, server.isRegistered(PlatformTracingJmxObjectNames.PROCESSOR_METRICS));
        status.put(TracingControlDomain.DIAGNOSTICS, server.isRegistered(PlatformTracingJmxObjectNames.DIAGNOSTICS));
        return Collections.unmodifiableMap(status);
    }

    // -- Sampling: reads -------------------------------------------------------

    public Optional<Double> getCurrentRatio() {
        return getDoubleAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplingRatio");
    }

    public Optional<Boolean> isSamplerEnabled() {
        return getBooleanAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplerEnabled");
    }

    public Optional<List<String>> getLiveDropPathPrefixes() {
        return getStringArrayAttribute(PlatformTracingJmxObjectNames.SAMPLING, "DropPathPrefixes")
                .map(PlatformTracingJmxClient::arrayToList);
    }

    public Optional<List<String>> getLiveForceRecordValues() {
        return getStringArrayAttribute(PlatformTracingJmxObjectNames.SAMPLING, "ForceRecordValues")
                .map(PlatformTracingJmxClient::arrayToList);
    }

    public Optional<Map<String, Double>> getLiveRouteRatios() {
        ObjectName name = PlatformTracingJmxObjectNames.SAMPLING;
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, "RouteRatios");
            if (value instanceof Map<?, ?> raw) {
                Map<String, Double> typed = new LinkedHashMap<>(raw.size());
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof Double ratio) {
                        typed.put(key, ratio);
                    }
                }
                return Optional.of(Collections.unmodifiableMap(typed));
            }
        } catch (Exception e) {
            log.debug("JMX: RouteRatios failed: {}", e.getMessage());
        }
        return Optional.empty();
    }

    public Optional<Long> getSamplingConfigVersion() {
        return getLongAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplingConfigVersion");
    }

    public Optional<String> getSamplingConfigLastUpdatedSource() {
        return getStringAttribute(PlatformTracingJmxObjectNames.SAMPLING, "SamplingConfigLastUpdatedSource");
    }

    public Optional<Long> getSamplerDecisionCount(String decision, String reason) {
        ObjectName name = PlatformTracingJmxObjectNames.SAMPLING;
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object result = server.invoke(name, "getSamplerDecisionCount",
                    new Object[]{decision, reason},
                    new String[]{String.class.getName(), String.class.getName()});
            return result instanceof Long l ? Optional.of(l) : Optional.empty();
        } catch (Exception e) {
            log.debug("JMX: getSamplerDecisionCount failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getSamplerDecisionCounts() {
        ObjectName name = PlatformTracingJmxObjectNames.SAMPLING;
        if (!server.isRegistered(name)) return Collections.emptyMap();
        try {
            Object result = server.getAttribute(name, "SamplerDecisionCounts");
            if (result instanceof Map) {
                return (Map<String, Long>) result;
            }
        } catch (Exception e) {
            log.debug("JMX: SamplerDecisionCounts failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // -- Scrubbing: reads ------------------------------------------------------

    public Optional<Boolean> isScrubbingEnabled() {
        return getBooleanAttribute(PlatformTracingJmxObjectNames.SCRUBBING, "ScrubbingEnabled");
    }

    public Optional<Long> getScrubbingConfigVersion() {
        return getLongAttribute(PlatformTracingJmxObjectNames.SCRUBBING, "ScrubbingConfigVersion");
    }

    public Optional<String> getScrubbingConfigLastUpdatedSource() {
        return getStringAttribute(PlatformTracingJmxObjectNames.SCRUBBING, "ScrubbingConfigLastUpdatedSource");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getScrubbingMetrics() {
        ObjectName name = PlatformTracingJmxObjectNames.SCRUBBING;
        if (!server.isRegistered(name)) return Collections.emptyMap();
        try {
            Object value = server.getAttribute(name, "ScrubbingMetrics");
            if (value instanceof Map<?, ?> raw) {
                Map<String, Long> typed = new LinkedHashMap<>(raw.size());
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof Long count) {
                        typed.put(key, count);
                    }
                }
                return Collections.unmodifiableMap(typed);
            }
        } catch (Exception e) {
            log.debug("JMX: ScrubbingMetrics failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // -- Validation: reads -----------------------------------------------------

    public Optional<Boolean> isValidationEnabled() {
        return getBooleanAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationEnabled");
    }

    public Optional<Boolean> isValidationStrict() {
        return getBooleanAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationStrict");
    }

    public Optional<Boolean> isValidationStrictRuntimeAllowed() {
        return getBooleanAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationStrictRuntimeAllowed");
    }

    public Optional<Long> getValidationConfigVersion() {
        return getLongAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationConfigVersion");
    }

    public Optional<String> getValidationConfigLastUpdatedSource() {
        return getStringAttribute(PlatformTracingJmxObjectNames.VALIDATION, "ValidationConfigLastUpdatedSource");
    }

    // -- Export: reads ---------------------------------------------------------

    public Optional<Map<String, Object>> getExportMetrics() {
        ObjectName name = PlatformTracingJmxObjectNames.EXPORT;
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Map<String, Object> export = new LinkedHashMap<>();
            int queueCapacity = getInt(name, "ExportQueueCapacity");
            int queueSize = getInt(name, "ExportQueueSize");
            export.put("queueCapacity", queueCapacity);
            export.put("queueSize", queueSize);
            export.put("queueUtilization", queueCapacity > 0 ? (double) queueSize / queueCapacity : 0.0);
            export.put("droppedOverflow", getLongRaw(name, "ExportDroppedOverflowTotal"));
            export.put("droppedAfterShutdown", getLongRaw(name, "ExportDroppedAfterShutdownTotal"));
            export.put("failures", getLongRaw(name, "ExportFailuresTotal"));
            export.put("timeouts", getLongRaw(name, "ExportTimeoutsTotal"));
            export.put("safeExporter", getSafeExporterMetrics());
            return Optional.of(export);
        } catch (Exception e) {
            log.debug("JMX: export metrics aggregation failed: {}", e.getMessage());
            return Optional.empty();
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Long> getSafeExporterMetrics() {
        ObjectName name = PlatformTracingJmxObjectNames.EXPORT;
        try {
            Object value = server.getAttribute(name, "SafeExporterMetrics");
            if (value instanceof Map) {
                return (Map<String, Long>) value;
            }
        } catch (Exception e) {
            log.debug("JMX: SafeExporterMetrics failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // -- ProcessorMetrics: reads -----------------------------------------------

    public Optional<Long> getProcessorErrorsTotal() {
        return getLongAttribute(PlatformTracingJmxObjectNames.PROCESSOR_METRICS, "ProcessorErrorsTotal");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getProcessorErrorsByName() {
        ObjectName name = PlatformTracingJmxObjectNames.PROCESSOR_METRICS;
        if (!server.isRegistered(name)) return Collections.emptyMap();
        try {
            Object value = server.getAttribute(name, "ProcessorErrorsByName");
            if (value instanceof Map<?, ?> raw) {
                Map<String, Long> typed = new LinkedHashMap<>(raw.size());
                for (Map.Entry<?, ?> entry : raw.entrySet()) {
                    if (entry.getKey() instanceof String key && entry.getValue() instanceof Long count) {
                        typed.put(key, count);
                    }
                }
                return Collections.unmodifiableMap(typed);
            }
        } catch (Exception e) {
            log.debug("JMX: ProcessorErrorsByName failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // -- Diagnostics: reads ----------------------------------------------------

    public Optional<Long> getInvalidConfigCount() {
        return getLongAttribute(PlatformTracingJmxObjectNames.DIAGNOSTICS, "InvalidConfigCount");
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getConfigReloadMetrics() {
        ObjectName name = PlatformTracingJmxObjectNames.DIAGNOSTICS;
        if (!server.isRegistered(name)) return Collections.emptyMap();
        try {
            Object value = server.getAttribute(name, "ConfigReloadMetrics");
            if (value instanceof Map) {
                return (Map<String, Long>) value;
            }
        } catch (Exception e) {
            log.debug("JMX: ConfigReloadMetrics failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    public List<String> getConfigAuditTrail() {
        ObjectName name = PlatformTracingJmxObjectNames.DIAGNOSTICS;
        if (!server.isRegistered(name)) return Collections.emptyList();
        try {
            Object value = server.getAttribute(name, "ConfigAuditTrail");
            if (value instanceof String[] arr) {
                return List.of(arr);
            }
        } catch (Exception e) {
            log.debug("JMX: ConfigAuditTrail failed: {}", e.getMessage());
        }
        return Collections.emptyList();
    }

    @SuppressWarnings("unchecked")
    public Map<String, Long> getSafeWrapperMetrics() {
        ObjectName name = PlatformTracingJmxObjectNames.DIAGNOSTICS;
        if (!server.isRegistered(name)) return Collections.emptyMap();
        try {
            Object value = server.getAttribute(name, "SafeWrapperMetrics");
            if (value instanceof Map) {
                return (Map<String, Long>) value;
            }
        } catch (Exception e) {
            log.debug("JMX: SafeWrapperMetrics failed: {}", e.getMessage());
        }
        return Collections.emptyMap();
    }

    // -- Internal helpers ------------------------------------------------------

    private Optional<Long> getLongAttribute(ObjectName name, String attribute) {
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof Long l ? Optional.of(l) : Optional.empty();
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return Optional.empty();
        }
    }

    private long getLongRaw(ObjectName name, String attribute) {
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof Long l ? l : 0L;
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return 0L;
        }
    }

    private int getInt(ObjectName name, String attribute) {
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof Integer i ? i : 0;
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return 0;
        }
    }

    private Optional<Double> getDoubleAttribute(ObjectName name, String attribute) {
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof Double d ? Optional.of(d) : Optional.empty();
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<Boolean> getBooleanAttribute(ObjectName name, String attribute) {
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof Boolean b ? Optional.of(b) : Optional.empty();
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String> getStringAttribute(ObjectName name, String attribute) {
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, attribute);
            return value instanceof String s ? Optional.of(s) : Optional.empty();
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
            return Optional.empty();
        }
    }

    private Optional<String[]> getStringArrayAttribute(ObjectName name, String attribute) {
        if (!server.isRegistered(name)) return Optional.empty();
        try {
            Object value = server.getAttribute(name, attribute);
            if (value instanceof String[] arr) {
                return Optional.of(arr.clone());
            }
        } catch (Exception e) {
            log.debug("JMX: {} failed: {}", attribute, e.getMessage());
        }
        return Optional.empty();
    }

    private static List<String> arrayToList(String[] arr) {
        return arr.length == 0 ? List.of() : List.of(arr);
    }
}
