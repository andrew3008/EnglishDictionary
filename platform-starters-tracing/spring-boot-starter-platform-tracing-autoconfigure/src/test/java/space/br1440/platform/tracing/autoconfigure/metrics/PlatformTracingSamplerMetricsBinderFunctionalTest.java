package space.br1440.platform.tracing.autoconfigure.metrics;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxObjectNames;
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
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class PlatformTracingSamplerMetricsBinderFunctionalTest {

    private final MBeanServer server = ManagementFactory.getPlatformMBeanServer();
    private ObjectName registeredName;
    private PlatformTracingSamplerMetricsBinder binder;
    private PlatformTracingJmxClient client;

    @AfterEach
    void cleanup() throws Exception {
        if (registeredName != null && server.isRegistered(registeredName)) {
            server.unregisterMBean(registeredName);
        }

        registeredName = null;
    }

    @Test
    void allElevenDecisionSeriesAreDecodedAndExposedWithExactValues() throws Exception {
        Map<String, Long> wireCounts = new LinkedHashMap<>();
        long value = 1;
        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            wireCounts.put(key.wireKey(), value++);
        }

        registerSamplingMBean(wireCounts, true, 1L);

        SimpleMeterRegistry registry = bindBinder();

        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            double expected = wireCounts.get(key.wireKey()).doubleValue();
            assertThat(decisionCounter(registry, key)).as("wireKey %s", key.wireKey()).isEqualTo(expected);
        }
    }

    @Test
    void absentSamplerOwnerYieldsZeroForAllElevenSeriesWithoutContractViolation() throws Exception {
        registerSamplingMBean(Map.of(), false, 3L);

        SimpleMeterRegistry registry = bindBinder();

        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            assertThat(decisionCounter(registry, key)).as("wireKey %s", key.wireKey()).isZero();
        }
    }

    @Test
    void sparseWireMapDefaultsMissingKeysToZeroNotAbsent() throws Exception {
        registerSamplingMBean(Map.of(SamplerDecisionKey.SAMPLED_FORCE_HEADER.wireKey(), 7L), true, 1L);

        SimpleMeterRegistry registry = bindBinder();

        assertThat(decisionCounter(registry, SamplerDecisionKey.SAMPLED_FORCE_HEADER)).isEqualTo(7.0);
        for (SamplerDecisionKey key : SamplerDecisionKey.values()) {
            if (key != SamplerDecisionKey.SAMPLED_FORCE_HEADER) {
                assertThat(decisionCounter(registry, key)).as("wireKey %s", key.wireKey()).isZero();
            }
        }
    }

    private static double decisionCounter(SimpleMeterRegistry registry, SamplerDecisionKey key) {
        String decisionTag = "RECORD_AND_SAMPLE".equals(key.decision()) ? "sampled" : "dropped";
        return registry.get("platform.tracing.sampler.decisions")
                .tags("decision", decisionTag, "reason", key.reason())
                .functionCounter()
                .count();
    }

    private SimpleMeterRegistry bindBinder() {
        client = new PlatformTracingJmxClient(server);
        AtomicLong nanoTime = new AtomicLong(0);
        CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource =
                new CoalescingJmxObservationSource<>(Optional::empty, nanoTime::get, 0L);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        binder = new PlatformTracingSamplerMetricsBinder(client, diagnosticsSource, nanoTime::get);
        binder.bindTo(registry);
        return registry;
    }

    private void registerSamplingMBean(Map<String, Long> decisionCounts, boolean ownerPresent, long generation)
            throws Exception {
        registeredName = PlatformTracingJmxObjectNames.SAMPLING;
        server.registerMBean(new FakeSamplingMBean(decisionCounts, ownerPresent, generation), registeredName);
    }

    private static final class FakeSamplingMBean implements DynamicMBean {

        private static final String ATTRIBUTE = "SamplingObservation";

        private final Map<String, Long> decisionCounts;
        private final boolean ownerPresent;
        private final long generation;

        FakeSamplingMBean(Map<String, Long> decisionCounts, boolean ownerPresent, long generation) {
            this.decisionCounts = decisionCounts;
            this.ownerPresent = ownerPresent;
            this.generation = generation;
        }

        @Override
        public Object getAttribute(String attribute) throws AttributeNotFoundException {
            if (!ATTRIBUTE.equals(attribute)) {
                throw new AttributeNotFoundException(attribute);
            }
            return buildComposite();
        }

        private CompositeData buildComposite() {
            try {
                TabularType stringLongMapType = stringLongMapType("space.br1440.platform.tracing.SamplerDecisionCounts", "SamplerDecisionCountEntry");
                TabularType stringDoubleMapType = stringDoubleMapType("space.br1440.platform.tracing.SamplingRouteRatios", "SamplingRouteRatioEntry");

                Map<String, OpenType<?>> itemTypes = new LinkedHashMap<>();
                itemTypes.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, SimpleType.INTEGER);
                itemTypes.put(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE, SimpleType.LONG);
                itemTypes.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, SimpleType.STRING);
                itemTypes.put(JmxObservationWireContract.ITEM_CAPTURE_STARTED_AT, SimpleType.LONG);
                itemTypes.put(JmxObservationWireContract.ITEM_CAPTURE_COMPLETED_AT, SimpleType.LONG);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED, SimpleType.BOOLEAN);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO, SimpleType.DOUBLE);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_ROUTE_RATIOS, stringDoubleMapType);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS, stringLongMapType);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE, SimpleType.STRING);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT, SimpleType.BOOLEAN);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_GENERATION, SimpleType.LONG);
                itemTypes.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_CAPTURED_OFFSET_NANOS, SimpleType.LONG);

                CompositeType type = new CompositeType(
                        JmxObservationWireContract.SAMPLING_COMPOSITE_TYPE_NAME,
                        "Синтетический SamplingObservation для функционального теста binder'а",
                        itemTypes.keySet().toArray(new String[0]),
                        itemTypes.keySet().toArray(new String[0]),
                        itemTypes.values().toArray(new OpenType<?>[0]));

                Map<String, Object> values = new LinkedHashMap<>();
                values.put(JmxObservationWireContract.ITEM_SCHEMA_VERSION, JmxObservationWireContract.SCHEMA_VERSION);
                values.put(JmxObservationWireContract.ITEM_CAPTURE_SEQUENCE, 1L);
                values.put(JmxObservationWireContract.ITEM_AGENT_INSTANCE_ID, "agent-functional-test");
                values.put(JmxObservationWireContract.ITEM_CAPTURE_STARTED_AT, 0L);
                values.put(JmxObservationWireContract.ITEM_CAPTURE_COMPLETED_AT, 0L);
                values.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_ENABLED, true);
                values.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLING_RATIO, 1.0);
                values.put(JmxObservationWireContract.SAMPLING_ITEM_ROUTE_RATIOS, stringDoubleMap(stringDoubleMapType, Map.of()));
                values.put(JmxObservationWireContract.SAMPLING_ITEM_DECISION_COUNTS, stringLongMap(stringLongMapType, decisionCounts));
                values.put(JmxObservationWireContract.SAMPLING_ITEM_CONFIG_LAST_UPDATED_SOURCE, "test");
                values.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_PRESENT, ownerPresent);
                values.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_GENERATION, generation);
                values.put(JmxObservationWireContract.SAMPLING_ITEM_SAMPLER_OWNER_CAPTURED_OFFSET_NANOS, 0L);

                return new CompositeDataSupport(type, values);
            } catch (OpenDataException e) {
                throw new IllegalStateException(e);
            }
        }

        private static TabularType stringLongMapType(String typeName, String rowTypeName) throws OpenDataException {
            CompositeType rowType = new CompositeType(rowTypeName, "row",
                    new String[]{"key", "value"}, new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.LONG});
            return new TabularType(typeName, "table", rowType, new String[]{"key"});
        }

        private static TabularType stringDoubleMapType(String typeName, String rowTypeName) throws OpenDataException {
            CompositeType rowType = new CompositeType(rowTypeName, "row",
                    new String[]{"key", "value"}, new String[]{"key", "value"},
                    new OpenType<?>[]{SimpleType.STRING, SimpleType.DOUBLE});
            return new TabularType(typeName, "table", rowType, new String[]{"key"});
        }

        private static TabularData stringLongMap(TabularType type, Map<String, Long> values) throws OpenDataException {
            TabularDataSupport table = new TabularDataSupport(type);
            for (Map.Entry<String, Long> entry : values.entrySet()) {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"}, new Object[]{entry.getKey(), entry.getValue()}));
            }
            return table;
        }

        private static TabularData stringDoubleMap(TabularType type, Map<String, Double> values) throws OpenDataException {
            TabularDataSupport table = new TabularDataSupport(type);
            for (Map.Entry<String, Double> entry : values.entrySet()) {
                table.put(new CompositeDataSupport(type.getRowType(),
                        new String[]{"key", "value"}, new Object[]{entry.getKey(), entry.getValue()}));
            }
            return table;
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
                    new MBeanAttributeInfo(ATTRIBUTE, "javax.management.openmbean.CompositeData", "fake", true, false, false)
            }, null, null, null);
        }
    }
}
