package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxContractPoint;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticCategory;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticKind;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxDiagnosticSnapshot;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport.JmxReadExecutor;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxObservationWireContract;

import javax.management.Attribute;
import javax.management.AttributeList;
import javax.management.AttributeNotFoundException;
import javax.management.DynamicMBean;
import javax.management.InvalidAttributeValueException;
import javax.management.MBeanAttributeInfo;
import javax.management.MBeanInfo;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JmxViewTypeNameMismatchWiringTest {

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
        JmxReadExecutor executor = new JmxReadExecutor(server);
        SamplingJmxView view = new SamplingJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.SAMPLING_OBSERVATION);
    }

    @Test
    void scrubbingTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ScrubbingObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ScrubbingJmxView view = new ScrubbingJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.SCRUBBING_OBSERVATION);
    }

    @Test
    void validationTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ValidationObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ValidationJmxView view = new ValidationJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.VALIDATION_OBSERVATION);
    }

    @Test
    void diagnosticsTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("DiagnosticsObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        DiagnosticsJmxView view = new DiagnosticsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.DIAGNOSTICS_OBSERVATION);
    }

    @Test
    void exportTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ExportObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void exportProcessorNestedMismatchIsContractViolation() throws Exception {
        CompositeData processor = compositeNamed("space.br1440.platform.tracing.NotExportProcessorValues");
        CompositeData exporter = compositeNamed(JmxObservationWireContract.EXPORT_EXPORTER_VALUES_TYPE_NAME);
        registerFakeMBean("ExportObservation", name -> exportFrame(processor, exporter));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void exportExporterNestedMismatchIsContractViolation() throws Exception {
        CompositeData processor = compositeNamed(JmxObservationWireContract.EXPORT_PROCESSOR_VALUES_TYPE_NAME);
        CompositeData exporter = compositeNamed("space.br1440.platform.tracing.NotSafeExporterValues");
        registerFakeMBean("ExportObservation", name -> exportFrame(processor, exporter));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ExportJmxView view = new ExportJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.EXPORT_OBSERVATION);
    }

    @Test
    void processorMetricsTopLevelMismatchIsContractViolation() throws Exception {
        registerFakeMBean("ProcessorMetricsObservation", name -> wrongTypeComposite());
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsWatchdogNestedMismatchIsContractViolation() throws Exception {
        CompositeData watchdog = compositeNamed("space.br1440.platform.tracing.NotProcessorWatchdogValues");
        CompositeData compositeValues = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME);
        CompositeData metrics = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME);
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(watchdog, compositeValues, metrics));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsCompositeNestedMismatchIsContractViolation() throws Exception {
        CompositeData watchdog = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME);
        CompositeData compositeValues = compositeNamed("space.br1440.platform.tracing.NotProcessorCompositeValues");
        CompositeData metrics = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_METRICS_VALUES_TYPE_NAME);
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(watchdog, compositeValues, metrics));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    @Test
    void processorMetricsMetricsNestedMismatchIsContractViolation() throws Exception {
        CompositeData watchdog = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_WATCHDOG_VALUES_TYPE_NAME);
        CompositeData compositeValues = compositeNamed(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_VALUES_TYPE_NAME);
        CompositeData metrics = compositeNamed("space.br1440.platform.tracing.NotProcessorMetricsValues");
        registerFakeMBean("ProcessorMetricsObservation", name -> processorMetricsFrame(watchdog, compositeValues, metrics));
        JmxReadExecutor executor = new JmxReadExecutor(server);
        ProcessorMetricsJmxView view = new ProcessorMetricsJmxView(executor, registeredName);

        assertThat(view.observation()).isEmpty();
        assertViolation(executor, JmxContractPoint.PROCESSOR_METRICS_OBSERVATION);
    }

    // -- Утверждение --------------------------------------------------------------------

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

    // -- Синтетические composite-заполнители --------------------------------------------

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
        return frameOf(JmxObservationWireContract.EXPORT_COMPOSITE_TYPE_NAME, Map.of(
                JmxObservationWireContract.EXPORT_ITEM_PROCESSOR, processor,
                JmxObservationWireContract.EXPORT_ITEM_EXPORTER, exporter));
    }

    private static CompositeData processorMetricsFrame(CompositeData watchdog, CompositeData compositeValues, CompositeData metrics) {
        return frameOf(JmxObservationWireContract.PROCESSOR_METRICS_COMPOSITE_TYPE_NAME, Map.of(
                JmxObservationWireContract.PROCESSOR_METRICS_ITEM_WATCHDOG, watchdog,
                JmxObservationWireContract.PROCESSOR_METRICS_ITEM_COMPOSITE, compositeValues,
                JmxObservationWireContract.PROCESSOR_METRICS_ITEM_METRICS, metrics));
    }

    /** Верхнеуровневый frame с корректным именем и минимальным набором frame-item'ов + nested-владельцами. */
    private static CompositeData frameOf(String typeName, Map<String, CompositeData> nestedItems) {
        try {
            Map<String, OpenType<?>> itemTypes = new LinkedHashMap<>();
            itemTypes.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, SimpleType.INTEGER);
            itemTypes.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, SimpleType.STRING);
            for (Map.Entry<String, CompositeData> entry : nestedItems.entrySet()) {
                itemTypes.put(entry.getKey(), entry.getValue().getCompositeType());
            }

            CompositeType type = new CompositeType(
                    typeName, "Синтетический верхнеуровневый frame",
                    itemTypes.keySet().toArray(new String[0]),
                    itemTypes.keySet().toArray(new String[0]),
                    itemTypes.values().toArray(new OpenType<?>[0]));

            Map<String, Object> values = new LinkedHashMap<>();
            values.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, JmxObservationWireContract.SCHEMA_VERSION);
            values.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, "agent-wiring-test");
            values.putAll(nestedItems);

            return new CompositeDataSupport(type, values);
        } catch (OpenDataException e) {
            throw new IllegalStateException(e);
        }
    }

    // -- Регистрация fake MBean, аналогично JmxReadExecutorTest --------------------------

    private void registerFakeMBean(String attributeName, java.util.function.Function<String, Object> supplier) throws Exception {
        registeredName = ObjectName.getInstance(
                "space.br1440.platform.tracing.test:type=JmxViewTypeNameMismatchWiringTest,attribute=" + attributeName);
        server.registerMBean(new FakeDynamicMBean(attributeName, supplier), registeredName);
    }

    /** Простой {@code DynamicMBean} с единственным настраиваемым атрибутом. */
    private static final class FakeDynamicMBean implements DynamicMBean {

        private final String attributeName;
        private final java.util.function.Function<String, Object> supplier;

        FakeDynamicMBean(String attributeName, java.util.function.Function<String, Object> supplier) {
            this.attributeName = attributeName;
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
            return new MBeanInfo(getClass().getName(), "fake", new MBeanAttributeInfo[]{
                    new MBeanAttributeInfo(attributeName, "javax.management.openmbean.CompositeData", "fake", true, false, false)
            }, null, null, null);
        }
    }
}
