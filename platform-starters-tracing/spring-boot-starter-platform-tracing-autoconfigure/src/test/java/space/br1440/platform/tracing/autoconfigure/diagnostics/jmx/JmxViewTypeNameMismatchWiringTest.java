package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanInfo;
import javax.management.MBeanNotificationInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenMBeanAttributeInfo;
import javax.management.openmbean.OpenMBeanAttributeInfoSupport;
import javax.management.openmbean.OpenMBeanConstructorInfo;
import javax.management.openmbean.OpenMBeanInfoSupport;
import javax.management.openmbean.OpenMBeanOperationInfo;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticCategory;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticKind;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutorTestFactory;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

class JmxViewTypeNameMismatchWiringTest {

    private static final String AGENT_INSTANCE_ID = "123e4567-e89b-12d3-a456-426614174000";

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private ObjectName registeredName;

    @AfterEach
    void cleanup() throws Exception {
        if (registeredName != null && server.isRegistered(registeredName)) {
            server.unregisterMBean(registeredName);
        }
        registeredName = null;
    }

    @Test
    void samplingTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("SamplingObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        SamplingJmxView view = new SamplingJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.SAMPLING_OBSERVATION);
    }

    @Test
    void scrubbingTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ScrubbingObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ScrubbingJmxView view = new ScrubbingJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.SCRUBBING_OBSERVATION);
    }

    @Test
    void validationTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ValidationObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ValidationJmxView view = new ValidationJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.VALIDATION_OBSERVATION);
    }

    @Test
    void diagnosticsTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("DiagnosticsObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        DiagnosticsJmxView view = new DiagnosticsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.DIAGNOSTICS_OBSERVATION);
    }

    @Test
    void exportTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ExportObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void exportProcessorNestedMismatchIsContractViolation() throws Exception {
        CompositeData processor = compositeNamed("space.br1440.platform.tracing.NotExportProcessorValues");
        CompositeData exporter = compositeNamed(JmxWireContract.OPEN_TYPE_SAFE_EXPORTER_VALUES_TYPE_NAME);
        registerFakeMBean("ExportObservation", name -> exportFrame(processor, exporter));
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void exportExporterNestedMismatchIsContractViolation() throws Exception {
        CompositeData processor = compositeNamed(JmxWireContract.OPEN_TYPE_EXPORT_PROCESSOR_VALUES_TYPE_NAME);
        CompositeData exporter = compositeNamed("space.br1440.platform.tracing.NotSafeExporterValues");
        registerFakeMBean("ExportObservation", name -> exportFrame(processor, exporter));
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void processorMetricsTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ProcessorMetricsObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsSpanTimeoutNestedMismatchIsContractViolation() throws Exception {
        CompositeData spanTimeout = compositeNamed("space.br1440.platform.tracing.NotProcessorSpanTimeoutValues");
        CompositeData compositeValues = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_COMPOSITE_VALUES_TYPE_NAME);
        CompositeData metrics = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_VALUES_TYPE_NAME);
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(spanTimeout, compositeValues, metrics));
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsCompositeNestedMismatchIsContractViolation() throws Exception {
        CompositeData spanTimeout = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_SPAN_TIMEOUT_VALUES_TYPE_NAME);
        CompositeData compositeValues = compositeNamed("space.br1440.platform.tracing.NotProcessorCompositeValues");
        CompositeData metrics = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_VALUES_TYPE_NAME);
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(spanTimeout, compositeValues, metrics));
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsMetricsNestedMismatchIsContractViolation() throws Exception {
        CompositeData spanTimeout = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_SPAN_TIMEOUT_VALUES_TYPE_NAME);
        CompositeData compositeValues = compositeNamed(JmxWireContract.OPEN_TYPE_PROCESSOR_COMPOSITE_VALUES_TYPE_NAME);
        CompositeData metrics = compositeNamed("space.br1440.platform.tracing.NotProcessorMetricsValues");
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(spanTimeout, compositeValues, metrics));
        JmxReadExecutor executor = JmxReadExecutorTestFactory.withoutAgentInstanceVerification(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    private static void assertViolation(JmxReadExecutor executor, JmxContractPoint point) {
        List<JmxDiagnosticSnapshot> snapshot = executor.diagnosticSnapshot();
        assertThat(snapshot)
                .as("diagnostic snapshot for %s", point)
                .anySatisfy(entry -> {
                    assertThat(entry.point()).isEqualTo(point);
                    assertThat(entry.category()).isEqualTo(JmxDiagnosticCategory.CONTRACT_VIOLATION);
                    assertThat(entry.kind()).isEqualTo(JmxDiagnosticKind.COMPOSITE_TYPE_NAME_MISMATCH);
                });
    }

    private static CompositeData wrongTypeComposite() {
        return compositeNamed("space.br1440.platform.tracing.NotTheExpectedType");
    }

    private static CompositeData compositeNamed(String typeName) {
        try {
            CompositeType type = new CompositeType(
                    typeName, "Синтетический минимальный composite-заполнитель",
                    new String[]{"marker"}, new String[]{"marker"}, new OpenType<?>[]{SimpleType.STRING});
            return new CompositeDataSupport(type, Map.of("marker", "value"));
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private static CompositeData exportFrame(CompositeData processor, CompositeData exporter) {
        return frameOf(JmxWireContract.OPEN_TYPE_EXPORT_OBSERVATION_TYPE_NAME, Map.of(
                JmxWireContract.FIELD_EXPORT_OBSERVATION_PROCESSOR, processor,
                JmxWireContract.FIELD_EXPORT_OBSERVATION_EXPORTER, exporter));
    }

    private static CompositeData processorMetricsFrame(CompositeData spanTimeout, CompositeData compositeValues, CompositeData metrics) {
        return frameOf(JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_OBSERVATION_TYPE_NAME, Map.of(
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_SPAN_TIMEOUT, spanTimeout,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_COMPOSITE, compositeValues,
                JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_METRICS, metrics));
    }

    private static CompositeData frameOf(String typeName, Map<String, CompositeData> nestedItems) {
        try {
            Map<String, OpenType<?>> itemTypes = new LinkedHashMap<>();
            itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID, SimpleType.STRING);
            if (JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_OBSERVATION_TYPE_NAME.equals(typeName)) {
                itemTypes.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE, SimpleType.LONG);
                itemTypes.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT, SimpleType.LONG);
                itemTypes.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT, SimpleType.LONG);
            }
            for (Map.Entry<String, CompositeData> entry : nestedItems.entrySet()) {
                itemTypes.put(entry.getKey(), entry.getValue().getCompositeType());
            }

            CompositeType type = new CompositeType(
                    typeName, "Synthetic top-level frame",
                    itemTypes.keySet().toArray(new String[0]),
                    itemTypes.keySet().toArray(new String[0]),
                    itemTypes.values().toArray(new OpenType<?>[0])
            );

            Map<String, Object> values = new LinkedHashMap<>();
            values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID, AGENT_INSTANCE_ID);
            if (JmxWireContract.OPEN_TYPE_PROCESSOR_METRICS_OBSERVATION_TYPE_NAME.equals(typeName)) {
                values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_SEQUENCE, 1L);
                values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_STARTED_AT, 1_700_000_000_000L);
                values.put(JmxWireContract.FIELD_PROCESSOR_METRICS_OBSERVATION_CAPTURE_COMPLETED_AT, 1_700_000_000_001L);
            }
            values.putAll(nestedItems);

            return new CompositeDataSupport(type, values);
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    private void registerFakeMBean(String attributeName, java.util.function.Function<String, Object> supplier) throws Exception {
        String attributePath = "space.br1440.platform.tracing.test:type=JmxViewTypeNameMismatchWiringTest,attribute=" + attributeName;
        registeredName = ObjectName.getInstance(attributePath);
        server.registerMBean(new FakeDynamicMBean(attributeName, supplier), registeredName);
    }

    private static final class FakeDynamicMBean implements DynamicMBean {

        private final String attributeName;
        private final OpenType<?> openType;
        private final java.util.function.Function<String, Object> supplier;

        FakeDynamicMBean(String attributeName, java.util.function.Function<String, Object> supplier) {
            this.attributeName = attributeName;
            this.openType = openType(attributeName);
            this.supplier = supplier;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (!attributeName.equals(attribute)) {
                throw new AttributeNotFoundException(attribute);
            }
            return supplier.apply(attribute);
        }

        @Override
        public void setAttribute(Attribute attribute) throws InvalidAttributeValueException {
            throw new InvalidAttributeValueException("read-only");
        }

        @Override
        public AttributeList getAttributes(String[] attributes) {
            return new AttributeList();
        }

        @Override
        public AttributeList setAttributes(AttributeList attributes) {
            return new AttributeList();
        }

        @Override
        public Object invoke(String actionName, Object[] params, String[] signature) {
            throw new UnsupportedOperationException();
        }

        @Override
        public MBeanInfo getMBeanInfo() {
            OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                    attributeName,
                    "fake",
                    openType,
                    true,
                    false,
                    false
            );

            return new OpenMBeanInfoSupport(
                    getClass().getName(),
                    "fake",
                    new OpenMBeanAttributeInfo[]{attribute},
                    new OpenMBeanConstructorInfo[0],
                    new OpenMBeanOperationInfo[0],
                    new MBeanNotificationInfo[0]
            );
        }

        private static OpenType<?> openType(String attributeName) {
            return switch (attributeName) {
                case "SamplingObservation" -> JmxOpenTypes.SAMPLING_OBSERVATION;
                case "ScrubbingObservation" -> JmxOpenTypes.SCRUBBING_OBSERVATION;
                case "ValidationObservation" -> JmxOpenTypes.VALIDATION_OBSERVATION;
                case "ExportObservation" -> JmxOpenTypes.EXPORT_OBSERVATION;
                case "ProcessorMetricsObservation" -> JmxOpenTypes.PROCESSOR_METRICS_OBSERVATION;
                case "DiagnosticsObservation" -> JmxOpenTypes.DIAGNOSTICS_OBSERVATION;
                default -> throw new IllegalArgumentException("Unknown observation attribute: " + attributeName);
            };
        }
    }
}
