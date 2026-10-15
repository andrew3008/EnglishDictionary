package space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.TabularDataSupport;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.parallel.ResourceLock;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsRevision;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsRejectionReason;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.SettingsUpdateStatus;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxOpenTypes;
import space.br1440.platform.tracing.otel.javaagent.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.otel.javaagent.logging.AgentLogCapture;
import space.br1440.platform.tracing.otel.javaagent.logging.schema.AgentEventCode;

@ResourceLock(AgentLogCapture.RESOURCE_LOCK)
final class RuntimePolicyRejectionTest {

    @RegisterExtension
    final AgentLogCapture capture = new AgentLogCapture();

    @ParameterizedTest
    @ValueSource(doubles = {Double.NaN, Double.POSITIVE_INFINITY, -0.001d, 1.0001d})
    void rejectsInvalidSamplingRatioWithoutChangingAcceptedState(double invalidRatio) throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsRevision acceptedBefore = store.current();
        String attemptId = UUID.randomUUID().toString();
        CompositeData validRequest = JmxSettingsMutationFixtures.request(
                acceptedBefore.agentInstanceId().toString(),
                acceptedBefore.acceptedSequence(),
                acceptedBefore.policy(),
                attemptId);
        CompositeData request = withPolicyField(
                validRequest,
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                invalidRatio
        );
        PlatformSettingsMutationMBean writer = writer(store);

        // when
        CompositeData response = invoke(writer, request);

        // then
        assertRejected(
                response,
                store,
                acceptedBefore,
                SettingsRejectionReason.INVALID_SAMPLING_RATIO,
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_RATIO,
                attemptId,
                Double.toString(invalidRatio)
        );
    }

    @Test
    void rejectsInvalidRouteRatioWithoutExposingRoutePrefix() throws Exception {
        // given
        String routePrefix = "https://user:secret@example.test/private?token=route-canary";
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsRevision acceptedBefore = store.current();
        String attemptId = UUID.randomUUID().toString();
        CompositeData validRequest = JmxSettingsMutationFixtures.request(
                acceptedBefore.agentInstanceId().toString(),
                acceptedBefore.acceptedSequence(),
                acceptedBefore.policy(),
                attemptId
        );
        Map<String, Object> row = new LinkedHashMap<>();
        row.put(JmxWireContract.FIELD_ROUTE_RATIO_ROUTE_PREFIX, routePrefix);
        row.put(JmxWireContract.FIELD_ROUTE_RATIO_RATIO, Double.NaN);
        TabularDataSupport routes = new TabularDataSupport(JmxOpenTypes.ROUTE_RATIOS);
        routes.put(new CompositeDataSupport(JmxOpenTypes.ROUTE_RATIO, row));
        CompositeData request = withPolicyField(
                validRequest,
                JmxWireContract.FIELD_RUNTIME_POLICY_SAMPLING_ROUTE_RATIOS,
                routes);
        PlatformSettingsMutationMBean writer = writer(store);

        // when
        CompositeData response = invoke(writer, request);

        // then
        assertRejected(
                response,
                store,
                acceptedBefore,
                SettingsRejectionReason.INVALID_ROUTE_RATIO,
                JmxWireContract.FIELD_ROUTE_RATIO_RATIO,
                attemptId,
                routePrefix,
                Double.toString(Double.NaN)
        );
    }

    @ParameterizedTest
    @ValueSource(strings = {"error", "STRICT"})
    void rejectsNonCanonicalValidationLevelWithoutChangingAcceptedState(String invalidLevel) throws Exception {
        // given
        TracingSettingsStore store = JmxSettingsMutationFixtures.store();
        TracingSettingsRevision acceptedBefore = store.current();
        String attemptId = UUID.randomUUID().toString();
        CompositeData validRequest = JmxSettingsMutationFixtures.request(
                acceptedBefore.agentInstanceId().toString(),
                acceptedBefore.acceptedSequence(),
                acceptedBefore.policy(),
                attemptId);
        CompositeData request = withPolicyField(
                validRequest,
                JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL,
                invalidLevel);
        PlatformSettingsMutationMBean writer = writer(store);

        // when
        CompositeData response = invoke(writer, request);

        // then
        assertRejected(
                response,
                store,
                acceptedBefore,
                SettingsRejectionReason.INVALID_VALIDATION_LEVEL,
                JmxWireContract.FIELD_RUNTIME_POLICY_VALIDATION_LEVEL,
                attemptId,
                invalidLevel
        );
    }

    private void assertRejected(CompositeData response,
                                TracingSettingsStore store,
                                TracingSettingsRevision acceptedBefore,
                                SettingsRejectionReason expectedReason,
                                String expectedPropertyName,
                                String attemptId,
                                String... rawValues) {
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_OUTCOME))
                .isEqualTo(SettingsUpdateStatus.REJECTED.name());
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_MESSAGE_CODE))
                .isEqualTo(expectedReason.name());
        assertThat((String[]) response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_VIOLATION_CODES))
                .containsExactly(expectedReason.name());
        assertThat(response.get(JmxWireContract.FIELD_APPLY_SNAPSHOT_RESPONSE_ACCEPTED_SEQUENCE))
                .isEqualTo(acceptedBefore.acceptedSequence());
        assertThat(store.current()).isSameAs(acceptedBefore);
        assertThat(response.toString()).doesNotContain(rawValues);
        assertThat(capture.events())
                .filteredOn(event -> "JMX_SETTINGS_POLICY_REJECTED".equals(event.eventName()))
                .singleElement()
                .satisfies(event -> {
                    assertThat(event.eventCode()).isEqualTo(AgentEventCode.CTRL_WRITER);
                    assertThat(event.fields())
                            .containsEntry("phase", "decode")
                            .containsEntry("attemptId", attemptId)
                            .containsEntry("messageCode", expectedReason.name())
                            .containsEntry("propertyName", expectedPropertyName);
                    assertThat(event.throwableDigest()).isNull();
                });
        assertThat(capture.rendered()).doesNotContain(rawValues);
    }

    private static PlatformSettingsMutationMBean writer(TracingSettingsStore store) {
        return new PlatformSettingsMutationMBean(new TracingSettingsUpdater(store, level -> {
        }));
    }

    private static CompositeData invoke(PlatformSettingsMutationMBean writer, CompositeData request) throws Exception {
        return (CompositeData) writer.invoke(
                JmxWireContract.OPERATION_SETTINGS_MUTATION_APPLY_SNAPSHOT,
                new Object[]{request},
                new String[]{CompositeData.class.getName()});
    }

    private static CompositeData withPolicyField(CompositeData request,
                                                 String field,
                                                 Object value) throws Exception {
        CompositeData policy = (CompositeData) request.get(
                JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY
        );

        Map<String, Object> policyFields = new LinkedHashMap<>();
        for (String policyField : policy.getCompositeType().keySet()) {
            policyFields.put(policyField, policy.get(policyField));
        }
        policyFields.put(field, value);
        CompositeData rewrittenPolicy = new CompositeDataSupport(policy.getCompositeType(), policyFields);

        Map<String, Object> requestFields = new LinkedHashMap<>();
        for (String requestField : request.getCompositeType().keySet()) {
            requestFields.put(requestField, request.get(requestField));
        }
        requestFields.put(JmxWireContract.FIELD_APPLY_SNAPSHOT_REQUEST_RUNTIME_POLICY, rewrittenPolicy);
        return new CompositeDataSupport(request.getCompositeType(), requestFields);
    }
}
