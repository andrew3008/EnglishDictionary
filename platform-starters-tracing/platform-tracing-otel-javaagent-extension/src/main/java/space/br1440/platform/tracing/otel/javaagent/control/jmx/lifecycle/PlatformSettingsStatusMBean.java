package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.ReflectionException;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.PlatformAgentLogger;
import space.br1440.platform.tracing.otel.javaagent.logging.event.ControlPlaneLogEvent;

public final class PlatformSettingsStatusMBean implements DynamicMBean {

    private static final PlatformAgentLogger log = PlatformAgentLogger.get(PlatformSettingsStatusMBean.class);

    private static final String SNAPSHOT_ATTRIBUTE = JmxWireContract.ATTRIBUTE_SETTINGS_STATUS_ACCEPTED_POLICY_SNAPSHOT;
    private static final MBeanInfo INFO = buildMBeanInfo();

    private final TracingSettingsUpdater updater;

    public PlatformSettingsStatusMBean(TracingSettingsUpdater updater) {
        this.updater = Objects.requireNonNull(updater, "updater");
    }

    @Override
    public Object getAttribute(String attribute) throws AttributeNotFoundException, MBeanException {
        if (!SNAPSHOT_ATTRIBUTE.equals(attribute)) {
            throw new AttributeNotFoundException("Unknown JMX settings status attribute");
        }

        try {
            return snapshot();
        } catch (RuntimeException failure) {
            logCaptureFailure(failure);

            throw neutralFailure(
                    "JMX_SETTINGS_STATUS_CAPTURE_FAILED",
                    "Settings status capture failed"
            );
        }
    }

    @Override
    public void setAttribute(Attribute attribute) throws AttributeNotFoundException {
        throw new AttributeNotFoundException("JMX settings status is read-only");
    }

    @Override
    public AttributeList getAttributes(String[] attributes) {
        AttributeList result = new AttributeList();
        if (attributes == null) {
            return result;
        }

        CompositeData captured;
        try {
            captured = snapshot();
        } catch (RuntimeException failure) {
            logCaptureFailure(failure);
            return result;
        }

        for (String attribute : attributes) {
            if (SNAPSHOT_ATTRIBUTE.equals(attribute)) {
                result.add(new Attribute(attribute, captured));
            }
        }

        return result;
    }

    @Override
    public AttributeList setAttributes(AttributeList attributes) {
        return new AttributeList();
    }

    @Override
    public Object invoke(String actionName,
                         Object[] params,
                         String[] signature) throws ReflectionException {
        NoSuchMethodException rootCause = new NoSuchMethodException("JMX settings status endpoint has no operations");
        throw new ReflectionException(rootCause);
    }

    @Override
    public MBeanInfo getMBeanInfo() {
        return INFO;
    }

    private CompositeData snapshot() {
        return encode(updater.current());
    }

    private static CompositeData encode(TracingSettingsRevision accepted) {
        try {
            Map<String, Object> fields = new LinkedHashMap<>();

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_ACCEPTED_SEQUENCE,
                    accepted.acceptedSequence()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_AGENT_INSTANCE_ID,
                    accepted.agentInstanceId().toString()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SOURCE_AUTHORITY,
                    accepted.sourceAuthority().name()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ENABLED,
                    accepted.samplingEnabled()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_RATIO,
                    accepted.samplingRatio()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_SAMPLING_ROUTE_RATIOS,
                    routeRatios(accepted)
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_VALIDATION_LEVEL,
                    accepted.validationLevel().name()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_EXPORTER_ENABLED,
                    accepted.exporterEnabled()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_TRACE_CONTROL_HEADERS_ENABLED,
                    accepted.traceControlHeadersEnabled()
            );

            fields.put(
                    JmxWireContract.FIELD_ACCEPTED_POLICY_SNAPSHOT_DIAGNOSTICS_LOG_LEVEL,
                    accepted.diagnosticsLogLevel()
            );

            return new CompositeDataSupport(JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT, fields);
        } catch (OpenDataException exception) {
            throw new IllegalStateException("Cannot encode accepted-policy snapshot", exception);
        }
    }

    private static TabularData routeRatios(TracingSettingsRevision accepted) throws OpenDataException {
        TabularDataSupport table = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        List<Map.Entry<String, Double>> entries = accepted.samplingRouteRatios().entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .toList();

        for (Map.Entry<String, Double> entry : entries) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, entry.getKey());
            row.put(JmxWireContract.FIELD_ROUTE_RATIO_RATIO, entry.getValue());
            table.put(new CompositeDataSupport(JmxOpenTypes.ROUTE_RATIO, row));
        }

        return table;
    }

    private static MBeanInfo buildMBeanInfo() {
        OpenMBeanAttributeInfo snapshot = new OpenMBeanAttributeInfoSupport(
                SNAPSHOT_ATTRIBUTE,
                "Immutable accepted-policy snapshot",
                JmxOpenTypes.ACCEPTED_POLICY_SNAPSHOT,
                true,
                false,
                false
        );

        return new OpenMBeanInfoSupport(
                PlatformSettingsStatusMBean.class.getName(),
                "Accepted runtime settings status",
                new OpenMBeanAttributeInfo[]{snapshot},
                new OpenMBeanConstructorInfo[0],
                new OpenMBeanOperationInfo[0],
                new MBeanNotificationInfo[0]
        );
    }

    private static void logCaptureFailure(RuntimeException failure) {
        log.log(ControlPlaneLogEvent.JMX_SETTINGS_STATUS_CAPTURE_FAILED,
                fields -> fields.cause(failure));
    }

    @SuppressWarnings("SameParameterValue")
    private static MBeanException neutralFailure(String code, String message) {
        return new MBeanException(null, code + ": " + message);
    }
}
