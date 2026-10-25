package space.br1440.platform.tracing.autoconfigure.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
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
import javax.management.openmbean.TabularData;
import javax.management.openmbean.TabularDataSupport;
import javax.management.openmbean.TabularType;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.DiagnosticsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PlatformTracingSamplerMetricsBinderFunctionalTest {

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
        PlatformTracingJmxClient client = new PlatformTracingJmxClient(
                server,
                mock(AgentReadinessCoherenceGuard.class)
        );

        AtomicLong nanoTime = new AtomicLong(0);
        CoalescingJmxObservationSource<DiagnosticsObservation> diagnosticsSource = new CoalescingJmxObservationSource<>(
                Optional::empty,
                nanoTime::get,
                0L
        );

        PlatformTracingSamplerMetricsBinder binder = new PlatformTracingSamplerMetricsBinder(
                client,
                diagnosticsSource,
                nanoTime::get
        );
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        binder.bindTo(registry);
        return registry;
    }

    private void registerSamplingMBean(Map<String, Long> decisionCounts, boolean ownerPresent, long generation)
            throws Exception {
        registeredName = JmxWireContract.SAMPLING_OBSERVATION_OBJECT_NAME;
        server.registerMBean(new FakeSamplingMBean(decisionCounts, ownerPresent, generation), registeredName);
    }

    private record FakeSamplingMBean(Map<String, Long> decisionCounts,
                                     boolean ownerPresent,
                                     long generation) implements DynamicMBean {

            private static final String ATTRIBUTE = "SamplingObservation";

        @Override
            public Object getAttribute(String attribute) throws AttributeNotFoundException {
                if (!ATTRIBUTE.equals(attribute)) {
                    throw new AttributeNotFoundException(attribute);
                }

                return buildComposite();
            }

            private CompositeData buildComposite() {
                try {
                    TabularType stringLongMapType = stringLongMapType();
                    Map<String, OpenType<?>> itemTypes = new LinkedHashMap<>();
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID, SimpleType.STRING);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED, SimpleType.BOOLEAN);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO, SimpleType.DOUBLE);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIO_COUNT, SimpleType.LONG);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS, stringLongMapType);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE, SimpleType.STRING);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT, SimpleType.BOOLEAN);
                    itemTypes.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION, SimpleType.LONG);

                    CompositeType type = new CompositeType(
                            JmxWireContract.OPEN_TYPE_SAMPLING_OBSERVATION_TYPE_NAME,
                            "Синтетический SamplingObservation для функционального теста binder'а",
                            itemTypes.keySet().toArray(new String[0]),
                            itemTypes.keySet().toArray(new String[0]),
                            itemTypes.values().toArray(new OpenType<?>[0])
                    );

                    Map<String, Object> values = new LinkedHashMap<>();
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_AGENT_INSTANCE_ID, AGENT_INSTANCE_ID);
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_ENABLED, true);
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_RATIO, 1.0);
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_ROUTE_RATIO_COUNT, 0L);
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_DECISION_COUNTS, stringLongMap(stringLongMapType, decisionCounts));
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLING_CONFIG_LAST_UPDATED_SOURCE, "test");
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_PRESENT, ownerPresent);
                    values.put(JmxWireContract.FIELD_SAMPLING_OBSERVATION_SAMPLER_OWNER_GENERATION, generation);

                    return new CompositeDataSupport(type, values);
                } catch (OpenDataException e) {
                    throw new IllegalStateException(e);
                }
            }

            private static TabularType stringLongMapType() throws OpenDataException {
                CompositeType rowType = new CompositeType("SamplerDecisionCountEntry", "row",
                        new String[]{"key", "value"}, new String[]{"key", "value"},
                        new OpenType<?>[]{SimpleType.STRING, SimpleType.LONG});
                return new TabularType(
                        "space.br1440.platform.tracing.SamplerDecisionCounts", "table", rowType, new String[]{"key"});
            }

            private static TabularData stringLongMap(TabularType type, Map<String, Long> values) throws OpenDataException {
                TabularDataSupport table = new TabularDataSupport(type);
                for (Map.Entry<String, Long> entry : values.entrySet()) {
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
                OpenMBeanAttributeInfo attribute = new OpenMBeanAttributeInfoSupport(
                        ATTRIBUTE,
                        "fake",
                        JmxOpenTypes.SAMPLING_OBSERVATION,
                        true,
                        false,
                        false);
                return new OpenMBeanInfoSupport(
                        getClass().getName(),
                        "fake",
                        new OpenMBeanAttributeInfo[]{attribute},
                        new OpenMBeanConstructorInfo[0],
                        new OpenMBeanOperationInfo[0],
                        new MBeanNotificationInfo[0]);
            }
        }
}
