package space.br1440.platform.tracing.autoconfigure.status;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.management.MBeanServer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.slf4j.LoggerFactory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceGuard;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessCoherenceTestSupport;
import space.br1440.platform.tracing.autoconfigure.agent.AgentReadinessSnapshot;
import space.br1440.platform.tracing.autoconfigure.agent.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingBootstrapSettings;
import space.br1440.platform.tracing.autoconfigure.bootstrap.TracingControlChannel;
import space.br1440.platform.tracing.autoconfigure.control.TracingConfigurationGuardException;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.AcceptedSettingsAuthority;
import space.br1440.platform.tracing.autoconfigure.control.settings.CompleteTracingSettings;
import space.br1440.platform.tracing.autoconfigure.control.settings.SettingsRevision;
import space.br1440.platform.tracing.autoconfigure.control.settings.TracingSettingsContractViolationException;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorMetricsObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.processor.ProcessorSpanTimeoutValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationObservation;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.validation.ValidationValues;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.JmxOwnerState;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.wire.ObservationCapture;
import space.br1440.platform.tracing.autoconfigure.jmx.contract.generated.JmxWireContract;
import space.br1440.platform.tracing.autoconfigure.support.ReadinessJmxTestFixtures;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.NoOpTracingRuntime;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

class TracingStatusAssemblerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final String AGENT_INSTANCE_ID = "97616500-30bf-4ff4-8847-2fe7cf114df9";
    private static final String NEXT_AGENT_INSTANCE_ID = "030948f5-7be8-4e9d-af7c-075a9d7841d4";

    @ParameterizedTest
    @EnumSource(TracingControlChannel.class)
    void controlChannelSerializesAsJsonString(TracingControlChannel channel) {
        // given
        TracingStatusSnapshot snapshot = assembler(
                true,
                channel,
                diagnostics(AgentRuntimeState.AGENT_MISSING)
        ).assemble();

        // when
        JsonNode node = OBJECT_MAPPER.valueToTree(Map.of("controlChannel", snapshot.controlChannel()));

        // then
        assertThat(node.get("controlChannel").isTextual()).isTrue();
        assertThat(node.get("controlChannel").asText()).isEqualTo(channel.name());
        assertThat(snapshot.controlChannel()).isEqualTo(channel);
    }

    @Test
    void reasonPriorityStartsWithDisabledThenAgentAvailability() {
        // given
        TracingStatusAssembler disabled = assembler(
                false,
                TracingControlChannel.NONE,
                diagnostics(AgentRuntimeState.AGENT_MISSING)
        );

        TracingStatusAssembler unavailable = assembler(
                true,
                TracingControlChannel.NONE,
                diagnostics(AgentRuntimeState.AGENT_MISSING)
        );

        // when
        // then
        assertThat(disabled.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.TRACING_DISABLED);
        assertAgentUnavailable(unavailable.assemble());
    }

    @Test
    void writeChannelDistinguishesAgentUnavailableAndSettingsUnreadable() {
        TracingStatusAssembler unavailable = assembler(
                true,
                TracingControlChannel.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.EXTENSION_FAILED)
        );

        TracingStatusAssembler notAccepted = assembler(
                true,
                TracingControlChannel.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.AGENT_READY)
        );

        assertAgentUnavailable(unavailable.assemble());
        assertThat(notAccepted.assemble().settings().reason())
                .isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
    }

    @Test
    void acceptedPolicyIsSecretSafeAndPostStartAgentLossIsObserved() {
        AcceptedSettings projection = acceptedPolicy();

        TracingStatusAssembler assembler = assembler(
                true,
                TracingControlChannel.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.AGENT_READY),
                projection
        );

        TracingStatusSnapshot initial = assembler.assemble();
        assertThat(initial.settings().available()).isTrue();
        assertThat(initial.settings().acceptedRevision().agentInstanceId())
                .isEqualTo(initial.agent().get("agentInstanceId"));
        assertThat(initial.settings().accepted().samplingRouteRatios()).hasSize(3);
        assertThat(initial.settings().sourceAuthority()).isEqualTo("CONFIG_SERVER");
        assertThat(initial.agent())
                .containsEntry("contractId", "platform-tracing-jmx-wire-contract")
                .containsEntry("agentInstanceId", AGENT_INSTANCE_ID)
                .containsEntry("evidenceGeneration", 1L)
                .containsEntry("batchId", "batch-1")
                .containsEntry("agentDistributionSha256", "a".repeat(64))
                .containsEntry("extensionDefiningLoader", "agent-loader@1")
                .containsEntry("sdkDefiningLoader", "sdk-loader@2");

        TracingStatusSnapshot degraded = assembler(
                true,
                TracingControlChannel.CONFIG_SERVER,
                diagnostics(AgentRuntimeState.AGENT_MISSING),
                projection
        ).assemble();
        assertThat(degraded.settings().available()).isFalse();
        assertAgentUnavailable(degraded);
    }

    @Test
    void runtimeValidationSectionIncludesViolationCounter() {
        PlatformTracingJmxClient jmxClient = jmxClient();
        ValidationValues values = new ValidationValues("ERROR", 42L, 7L, "accepted-state");
        when(jmxClient.validation().observation()).thenReturn(Optional.of(
                new ValidationObservation(AGENT_INSTANCE_ID, new JmxOwnerState.Present<>(values, 1L))
        ));

        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingControlChannel.NONE
        );

        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient,
                Optional::empty,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard()
        );

        @SuppressWarnings("unchecked")
        Map<String, Object> validation = (Map<String, Object>) assembler.assemble().runtime().get("validation");
        assertThat(validation).containsEntry("violationsTotal", 42L);
    }

    @Test
    void runtimeSpanTimeoutSectionExposesLiveAndBoundedState() {
        // given
        PlatformTracingJmxClient jmxClient = jmxClient();
        ProcessorSpanTimeoutValues values = new ProcessorSpanTimeoutValues(
                new ProcessorSpanTimeoutValues.ForceEnd(11L, 4L, 13L),
                new ProcessorSpanTimeoutValues.Tracking(3, 1, 7, 10_000, 9L, 60_000, 1_024, 0L),
                new ProcessorSpanTimeoutValues.Rejections(5L, 2L, 8L),
                new ProcessorSpanTimeoutValues.Scanner(6L, 1_700_000_000_004L, "RUNNING"));
        when(jmxClient.processorMetrics().observation()).thenReturn(Optional.of(
                        new ProcessorMetricsObservation(
                                new ObservationCapture(AGENT_INSTANCE_ID, 17L, 1_700_000_000_000L, 1_700_000_000_005L),
                                new JmxOwnerState.Present<>(values, 2L),
                                new JmxOwnerState.Absent<>(0L),
                                new JmxOwnerState.Absent<>(0L)
                        )
                )
        );
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingControlChannel.NONE);
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient,
                Optional::empty,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard());

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        @SuppressWarnings("unchecked")
        Map<String, Object> spanTimeout = (Map<String, Object>) snapshot.runtime().get("spanTimeout");
        assertThat(spanTimeout)
                .containsEntry("present", true)
                .containsEntry("forcedSpanEndAttemptsTotal", 11L)
                .containsEntry("forcedTraceEndAttemptsTotal", 4L)
                .containsEntry("activeSpanCount", 3)
                .containsEntry("activeTraceCount", 1)
                .containsEntry("trackedTraceCount", 7)
                .containsEntry("trackedTraceCapacity", 10_000)
                .containsEntry("trackingEvictedTotal", 9L)
                .containsEntry("trackedSpanCapacity", 60_000)
                .containsEntry("trackedSpanPerTraceCapacity", 1_024)
                .containsEntry("spansRejectedPerTraceTotal", 5L)
                .containsEntry("spansRejectedGlobalTotal", 2L)
                .containsEntry("spansRejectedTraceCapacityTotal", 8L)
                .containsEntry("spanLedgerInvariantViolationTotal", 0L)
                .containsEntry("scanFailuresTotal", 6L)
                .containsEntry("forceEndFailuresTotal", 13L)
                .containsEntry("lastSuccessfulBackgroundScanEpochMillis", 1_700_000_000_004L)
                .containsEntry("scannerState", "RUNNING");
        assertThat(captureOf(spanTimeout))
                .containsEntry("agentInstanceId", AGENT_INSTANCE_ID)
                .containsEntry("sequence", 17L)
                .containsEntry("startedAtEpochMillis", 1_700_000_000_000L)
                .containsEntry("completedAtEpochMillis", 1_700_000_000_005L);
    }

    @Test
    void spanTimeoutCaptureIsPublishedWhenOwnerIsAbsentAndBlankWhenObservationIsUnreadable() {
        // given
        PlatformTracingJmxClient absentOwner = jmxClient();
        when(absentOwner.processorMetrics().observation()).thenReturn(Optional.of(
                new ProcessorMetricsObservation(
                        new ObservationCapture(AGENT_INSTANCE_ID, 5L, 1_700_000_000_000L, 1_700_000_000_002L),
                        new JmxOwnerState.Absent<>(0L),
                        new JmxOwnerState.Absent<>(0L),
                        new JmxOwnerState.Absent<>(0L))));

        PlatformTracingJmxClient unreadable = jmxClient();
        when(unreadable.processorMetrics().observation()).thenReturn(Optional.empty());

        // when
        Map<String, Object> absentOwnerSpanTimeout = spanTimeoutSectionOf(absentOwner);
        Map<String, Object> unreadableSpanTimeout = spanTimeoutSectionOf(unreadable);

        // then
        assertThat(absentOwnerSpanTimeout).containsEntry("present", false);
        assertThat(captureOf(absentOwnerSpanTimeout))
                .containsEntry("agentInstanceId", AGENT_INSTANCE_ID)
                .containsEntry("sequence", 5L)
                .containsEntry("startedAtEpochMillis", 1_700_000_000_000L)
                .containsEntry("completedAtEpochMillis", 1_700_000_000_002L);

        assertThat(unreadableSpanTimeout).containsEntry("present", false);
        assertThat(captureOf(unreadableSpanTimeout))
                .containsEntry("agentInstanceId", null)
                .containsEntry("sequence", null)
                .containsEntry("startedAtEpochMillis", null)
                .containsEntry("completedAtEpochMillis", null);
    }

    private static Map<String, Object> spanTimeoutSectionOf(PlatformTracingJmxClient jmxClient) {
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingControlChannel.NONE);
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient,
                Optional::empty,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard());

        @SuppressWarnings("unchecked")
        Map<String, Object> spanTimeout = (Map<String, Object>) assembler.assemble().runtime().get("spanTimeout");
        return spanTimeout;
    }

    private static Map<String, Object> captureOf(Map<String, Object> spanTimeoutSection) {
        @SuppressWarnings("unchecked")
        Map<String, Object> capture = (Map<String, Object>) spanTimeoutSection.get("capture");
        return capture;
    }

    @Test
    void mixedReadinessGenerationIsNeverPublishedAsReadyStatus() throws Exception {
        // given
        MBeanServer server = mock(MBeanServer.class);
        when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                .thenReturn(ReadinessJmxTestFixtures.metadata());
        when(server.getAttribute(
                JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                .thenReturn(
                        ReadinessJmxTestFixtures.readySnapshot(AGENT_INSTANCE_ID, 1L, "batch-1"),
                        ReadinessJmxTestFixtures.readySnapshot(AGENT_INSTANCE_ID, 1L, "batch-1"),
                        ReadinessJmxTestFixtures.readySnapshot(NEXT_AGENT_INSTANCE_ID, 2L, "batch-2")
                );

        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                true,
                TracingControlChannel.CONFIG_SERVER
        );

        AcceptedSettings projection = acceptedPolicy();

        AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
        AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                settings,
                jmxClient(),
                () -> Optional.of(projection),
                diagnostics(AgentRuntimeState.AGENT_READY),
                coherenceGuard
        );

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        assertThat(snapshot.agent())
                .containsEntry("state", AgentRuntimeState.EXTENSION_INCOMPATIBLE.name())
                .containsEntry("failureCode", AgentReadinessCoherenceGuard.MIXED_GENERATION);
        assertThat(snapshot.runtime()).isEmpty();
        assertThat(snapshot.settings().available()).isFalse();
        assertAgentUnavailable(snapshot);
        assertThat(snapshot.jmxContractDiagnostics()).isEmpty();
    }

    @Test
    void settingsContractFailureDoesNotEscapeAndReportsSettingsUnreadable() {
        // given
        AcceptedSettingsStatusReader reader = () -> {
            throw new TracingSettingsContractViolationException("STATUS_METADATA_NOT_PASSED");
        };
        TracingStatusAssembler assembler = assembler(reader);

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        assertThat(snapshot.settings().available()).isFalse();
        assertThat(snapshot.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
    }

    @Test
    void settingsGuardFailureDoesNotEscapeAndReportsAgentUnavailable() {
        // given
        AcceptedSettingsStatusReader reader = () -> {
            throw new TracingConfigurationGuardException("STATUS_TRANSPORT_FAILED");
        };
        TracingStatusAssembler assembler = assembler(reader);

        // when
        TracingStatusSnapshot snapshot = assembler.assemble();

        // then
        assertAgentUnavailable(snapshot);
        assertThat(snapshot.agent())
                .containsEntry("failureCode", AgentReadinessCoherenceGuard.ANCHOR_NOT_PINNED);
    }

    @Test
    void settingsCoherenceFailureIsNotReportedAsSettingsUnreadable() {
        AgentReadinessCoherenceGuard coherenceGuard = stableCoherenceGuard();
        AcceptedSettingsStatusReader reader = () -> {
            coherenceGuard.requirePinnedAgentInstanceId(NEXT_AGENT_INSTANCE_ID);
            return Optional.of(acceptedPolicy());
        };
        TracingStatusAssembler assembler = new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                new TracingBootstrapSettings(true, TracingControlChannel.NONE),
                jmxClient(),
                reader,
                diagnostics(AgentRuntimeState.AGENT_READY),
                coherenceGuard
        );

        TracingStatusSnapshot snapshot = assembler.assemble();

        assertThat(snapshot.agent())
                .containsEntry("state", AgentRuntimeState.EXTENSION_INCOMPATIBLE.name())
                .containsEntry("failureCode", AgentReadinessCoherenceGuard.JMX_AGENT_INSTANCE_MISMATCH);
        assertAgentUnavailable(snapshot);
    }

    @Test
    void repeatedSettingsReadFailureLogsWarnThenDebug() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsStatusResolver.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AcceptedSettingsStatusReader reader = () -> {
            throw new IllegalStateException("Synthetic settings read failure");
        };
        TracingStatusAssembler assembler = assembler(reader);

        try {
            // when
            TracingStatusSnapshot first = assembler.assemble();
            TracingStatusSnapshot second = assembler.assemble();

            // then
            assertThat(first.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
            assertThat(second.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.DEBUG).hasSize(1);
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void settingsReadRecoveryResetsFailureTransitionLogging() {
        // given
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsStatusResolver.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AtomicInteger reads = new AtomicInteger();
        AcceptedSettingsStatusReader reader = () -> {
            int read = reads.getAndIncrement();
            if (read == 1) {
                return Optional.of(acceptedPolicy());
            }

            throw new IllegalStateException("Synthetic settings read failure");
        };
        TracingStatusAssembler assembler = assembler(reader);

        try {
            // when
            TracingStatusSnapshot first = assembler.assemble();
            TracingStatusSnapshot recovered = assembler.assemble();
            TracingStatusSnapshot failedAgain = assembler.assemble();

            // then
            assertThat(first.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
            assertThat(recovered.settings().available()).isTrue();
            assertThat(failedAgain.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(2);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.INFO).hasSize(1);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.DEBUG).isEmpty();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    @Test
    void guardFailureDoesNotCloseTheUnreadableEpisode() {
        Logger logger = (Logger) LoggerFactory.getLogger(SettingsStatusResolver.class);
        Level previousLevel = logger.getLevel();
        logger.setLevel(Level.DEBUG);

        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);

        AtomicInteger reads = new AtomicInteger();
        AcceptedSettingsStatusReader reader = () -> {
            int read = reads.getAndIncrement();
            if (read == 0) {
                throw new IllegalStateException("Synthetic settings read failure");
            }
            if (read == 1) {
                throw new TracingConfigurationGuardException("STATUS_TRANSPORT_FAILED");
            }
            return Optional.of(acceptedPolicy());
        };
        TracingStatusAssembler assembler = assembler(reader);

        try {
            TracingStatusSnapshot unreadable = assembler.assemble();
            TracingStatusSnapshot guardFailure = assembler.assemble();
            TracingStatusSnapshot recovered = assembler.assemble();

            assertThat(unreadable.settings().reason()).isEqualTo(SettingsAbsenceReason.SETTINGS_UNREADABLE);
            assertAgentUnavailable(guardFailure);
            assertThat(recovered.settings().available()).isTrue();
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.WARN).hasSize(1);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.INFO).hasSize(1);
            assertThat(appender.list).filteredOn(event -> event.getLevel() == Level.DEBUG).isEmpty();
        } finally {
            logger.detachAppender(appender);
            logger.setLevel(previousLevel);
            appender.stop();
        }
    }

    private static PlatformTracingJmxClient jmxClient() {
        PlatformTracingJmxClient client = mock(PlatformTracingJmxClient.class, RETURNS_DEEP_STUBS);
        when(client.jmxContractDiagnostics()).thenReturn(List.of());
        return client;
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingControlChannel controlChannel,
                                                    TracingDeploymentDiagnostics diagnostics) {
        return assembler(enabled, controlChannel, diagnostics, null);
    }

    private static TracingStatusAssembler assembler(boolean enabled,
                                                    TracingControlChannel controlChannel,
                                                    TracingDeploymentDiagnostics diagnostics,
                                                    AcceptedSettings projection) {
        TracingBootstrapSettings settings = new TracingBootstrapSettings(
                enabled,
                controlChannel);

        TracingRuntime runtime = enabled
                ? NoOpTracingRuntime.noop()
                : NoOpTracingRuntime.disabledByConfiguration("test-disabled");

        return new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                runtime,
                settings,
                jmxClient(),
                () -> Optional.ofNullable(projection),
                diagnostics,
                stableCoherenceGuard()
        );
    }

    private static TracingStatusAssembler assembler(AcceptedSettingsStatusReader reader) {
        return new TracingStatusAssembler(
                NoopTraceOperations.INSTANCE,
                NoOpTracingRuntime.noop(),
                new TracingBootstrapSettings(true, TracingControlChannel.NONE),
                jmxClient(),
                reader,
                diagnostics(AgentRuntimeState.AGENT_READY),
                stableCoherenceGuard()
        );
    }

    private static AgentReadinessCoherenceGuard stableCoherenceGuard() {
        try {
            MBeanServer server = mock(MBeanServer.class);
            when(server.getMBeanInfo(JmxWireContract.EXTENSION_READINESS_OBJECT_NAME))
                    .thenReturn(ReadinessJmxTestFixtures.metadata());

            when(server.getAttribute(
                    JmxWireContract.EXTENSION_READINESS_OBJECT_NAME,
                    JmxWireContract.ATTRIBUTE_EXTENSION_READINESS_READINESS_SNAPSHOT))
                    .thenReturn(ReadinessJmxTestFixtures.readySnapshot(AGENT_INSTANCE_ID, 1L, "batch-1"));

            AgentReadinessCoherenceGuard coherenceGuard = new AgentReadinessCoherenceGuard(server);
            AgentReadinessCoherenceTestSupport.pinCurrent(coherenceGuard);
            return coherenceGuard;
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot prepare stable readiness coherence guard", exception);
        }
    }

    @Test
    void endpointAbsentAgentSectionOmitsSnapshotFields() {
        // given
        TracingDeploymentDiagnostics diagnostics = TracingDeploymentDiagnostics.withoutAgentData(
                true,
                false,
                AgentRuntimeState.AGENT_MISSING,
                "CONTROLLED_AGENT_MISSING");

        // when
        Map<String, Object> agent = assembler(true, TracingControlChannel.NONE, diagnostics).assemble().agent();

        // then
        assertThat(agent).containsOnlyKeys(
                "state",
                "markerVisible",
                "readinessEndpointPresent",
                "failureCode");
        assertThat(agent)
                .containsEntry("state", AgentRuntimeState.AGENT_MISSING.name())
                .containsEntry("markerVisible", false)
                .containsEntry("readinessEndpointPresent", false)
                .containsEntry("failureCode", "CONTROLLED_AGENT_MISSING");
    }

    @Test
    void unreadableAgentSectionKeepsEndpointPresenceAndOmitsSnapshotFields() {
        // given
        TracingDeploymentDiagnostics diagnostics = new TracingDeploymentDiagnostics(
                true,
                true,
                AgentRuntimeState.EXTENSION_INCOMPATIBLE,
                new TracingDeploymentDiagnostics.SnapshotUnreadable("READINESS_INVOCATION_FAILED"));

        // when
        Map<String, Object> agent = assembler(true, TracingControlChannel.NONE, diagnostics).assemble().agent();

        // then
        assertThat(agent).containsOnlyKeys(
                "state",
                "markerVisible",
                "readinessEndpointPresent",
                "failureCode");
        assertThat(agent)
                .containsEntry("state", AgentRuntimeState.EXTENSION_INCOMPATIBLE.name())
                .containsEntry("markerVisible", true)
                .containsEntry("readinessEndpointPresent", true)
                .containsEntry("failureCode", "READINESS_INVOCATION_FAILED");
    }

    @Test
    void agentSectionReportsStarterFailureCodeWhenSnapshotFailureCodeDiffers() {
        // given
        AgentReadinessSnapshot snapshot = new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.ContractIdentity("platform-tracing-jmx-wire-contract", "schema-digest"),
                new AgentReadinessSnapshot.AnchorIdentity(AGENT_INSTANCE_ID, 4L, "batch-4"),
                new AgentReadinessSnapshot.LifecycleState(
                        "FAILED",
                        "AGENT_EMITTED",
                        "AGENT_PRIMARY",
                        Set.of(),
                        Set.of()),
                new AgentReadinessSnapshot.DistributionIdentity("a".repeat(64), "2.30.0", "1.2.3"),
                new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", "sdk-loader@2"));
        TracingDeploymentDiagnostics diagnostics = new TracingDeploymentDiagnostics(
                true,
                true,
                AgentRuntimeState.EXTENSION_FAILED,
                TracingDeploymentDiagnostics.Observed.of(snapshot, "STARTER_DECISION"));

        // when
        Map<String, Object> agent = assembler(true, TracingControlChannel.NONE, diagnostics).assemble().agent();

        // then
        assertThat(agent)
                .containsEntry("failureCode", "STARTER_DECISION")
                .containsEntry("readinessEndpointPresent", true)
                .containsEntry("lifecycle", "FAILED")
                .containsEntry("evidenceGeneration", 4L)
                .doesNotContainValue("AGENT_EMITTED")
                .doesNotContainValue("AGENT_PRIMARY");
    }

    private static TracingDeploymentDiagnostics diagnostics(AgentRuntimeState state) {
        boolean ready = state == AgentRuntimeState.AGENT_READY;
        return new TracingDeploymentDiagnostics(
                ready,
                ready,
                state,
                ready
                        ? TracingDeploymentDiagnostics.Observed.of(readySnapshot(), "")
                        : new TracingDeploymentDiagnostics.EndpointAbsent("TEST_FAILURE")
        );
    }

    private static AgentReadinessSnapshot readySnapshot() {
        return new AgentReadinessSnapshot(
                new AgentReadinessSnapshot.ContractIdentity(
                        "platform-tracing-jmx-wire-contract",
                        "schema-digest"
                ),
                new AgentReadinessSnapshot.AnchorIdentity(AGENT_INSTANCE_ID, 1L, "batch-1"),
                new AgentReadinessSnapshot.LifecycleState("READY", "", "", Set.of(), Set.of()),
                new AgentReadinessSnapshot.DistributionIdentity("a".repeat(64), "2.30.0", "1.2.3"),
                new AgentReadinessSnapshot.ClassloaderTopology("agent-loader@1", "sdk-loader@2")
        );
    }

    private static AcceptedSettings acceptedPolicy() {
        return new AcceptedSettings(
                new SettingsRevision(AGENT_INSTANCE_ID, 7L),
                CompleteTracingSettings.builder()
                        .samplingEnabled(true)
                        .samplingRatio(0.25d)
                        .samplingRouteRatios(Map.of("/a", 0.1d, "/b", 0.2d, "/c", 0.3d))
                        .validationLevel("WARNING")
                        .exporterEnabled(true)
                        .traceControlHeadersEnabled(true)
                        .diagnosticsLogLevel("INFO")
                        .build(),
                AcceptedSettingsAuthority.CONFIG_SERVER
        );
    }

    private static void assertAgentUnavailable(TracingStatusSnapshot snapshot) {
        assertThat(snapshot.settings().reason()).isEqualTo(SettingsAbsenceReason.AGENT_UNAVAILABLE);
        assertThat(snapshot.agent().get("failureCode")).isInstanceOf(String.class);
        assertThat((String) snapshot.agent().get("failureCode")).isNotBlank();
    }
}
