package space.br1440.platform.tracing.e2e.configserver;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.cloud.context.environment.EnvironmentChangeEvent;
import org.springframework.cloud.context.refresh.ContextRefresher;

import space.br1440.platform.tracing.e2e.configserver.support.ConfigClientHarness;
import space.br1440.platform.tracing.e2e.configserver.support.ConfigServerResponseStub;
import space.br1440.platform.tracing.e2e.configserver.support.EvidenceRecorder;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.KeyEvidence;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.MarkerEvidence;
import space.br1440.platform.tracing.e2e.configserver.support.ProvenanceInspector.ProvenanceCategory;

class ConfigServerProvenanceCharacterizationTest {

    private static final Object SYSTEM_PROPERTIES_MONITOR = new Object();
    private static final String POLICY_RATIO = "platform.tracing.policy.sampling.ratio";
    private static final String POLICY_ROUTE = "platform.tracing.policy.sampling.route-ratio";
    private static final String STRUCTURAL_QUEUE = "otel.bsp.max.queue.size";
    private static final String EXPLICIT_RATIO_DEFAULT = "1.0";

    @Test
    void t1_pureRemotePolicyHasTrustedEffectiveAndDesiredViews() throws Exception {
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "0.25", POLICY_ROUTE, "0.50"),
                "remote-v1",
                "state-v1"
        ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
            KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
            MarkerEvidence marker = ProvenanceInspector.marker(client.environment());

            assertThat(marker.present()).isTrue();
            assertThat(marker.version()).isEqualTo("remote-v1");
            assertThat(marker.state()).isEqualTo("state-v1");
            assertThat(evidence.effectiveCategory()).isEqualTo(ProvenanceCategory.CONFIG_SERVER_REMOTE);
            assertThat(evidence.effectiveValue()).isEqualTo("0.25");
            assertThat(evidence.remoteDesiredValue()).isEqualTo("0.25");
            assertThat(evidence.localOverride()).isFalse();
            record("T1", client, evidence, marker, POLICY_RATIO, POLICY_ROUTE);
        }
    }

    @Test
    void t2_commandLineWinsWhileLocalYamlPrecedenceIsCharacterized(@TempDir Path directory) throws Exception {
        Files.writeString(
                directory.resolve("application.yml"),
                "platform:\n  tracing:\n    policy:\n      sampling:\n"
                        + "        ratio: local-yaml-ratio\n        route-ratio: local-yaml-route\n",
                StandardCharsets.UTF_8
        );
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "remote-ratio", POLICY_ROUTE, "remote-route"),
                "remote-v2",
                "state-v2"
        ); ConfigClientHarness client = ConfigClientHarness.start(
                server.uri(),
                ConfigClientHarness.additionalLocation(directory),
                "--" + POLICY_RATIO + "=command-line-ratio"
        )) {
            KeyEvidence commandLine = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
            KeyEvidence localYaml = ProvenanceInspector.inspect(client.environment(), POLICY_ROUTE);

            assertThat(commandLine.effectiveCategory()).isEqualTo(ProvenanceCategory.COMMAND_LINE);
            assertThat(commandLine.remoteDesiredValue()).isEqualTo("remote-ratio");
            assertThat(commandLine.localOverride()).isTrue();
            assertThat(commandLine.conflict()).isTrue();
            assertThat(localYaml.remoteDesiredValue()).isEqualTo("remote-route");
            assertThat(localYaml.effectiveValue()).isEqualTo("remote-route");
            assertThat(localYaml.effectiveCategory()).isEqualTo(ProvenanceCategory.CONFIG_SERVER_REMOTE);
            assertThat(localYaml.localOverride()).isFalse();

            MarkerEvidence marker = ProvenanceInspector.marker(client.environment());
            List<String> tree = ProvenanceInspector.sanitizedTree(
                    client.environment(),
                    POLICY_RATIO,
                    POLICY_ROUTE
            );
            assertThat(indexOfSource(tree, "configserver:stub:application.yml"))
                    .isLessThan(indexOfSource(tree, "Config resource 'file"));
            EvidenceRecorder.record(
                    "T2-local-yaml",
                    localYaml,
                    marker,
                    tree,
                    "CONFIG_SERVER guard decision for command-line override=FAIL_CLOSED; "
                            + "local YAML actual category=" + localYaml.effectiveCategory()
            );
            record("T2-command-line", client, commandLine, marker, POLICY_RATIO, POLICY_ROUTE);
        }
    }

    @Test
    void t3_refreshRemovalRebuildsFullSnapshotAndSelectsExplicitDefault() throws Exception {
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "0.10"),
                "remote-v3-before",
                "state-v3-before"
        ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
            AtomicReference<Set<String>> eventKeys = captureEventKeys(client);
            server.respond(Map.of(), "remote-v3-after", "state-v3-after");

            Set<String> refreshKeys = refresh(client);
            KeyEvidence after = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
            String rebuiltRatio = after.effectiveValue() == null
                    ? EXPLICIT_RATIO_DEFAULT
                    : String.valueOf(after.effectiveValue());

            assertThat(refreshKeys).contains(POLICY_RATIO);
            assertThat(eventKeys.get()).contains(POLICY_RATIO);
            assertThat(after.effectiveValue()).isNull();
            assertThat(after.remoteDesiredValue()).isNull();
            assertThat(rebuiltRatio).isEqualTo(EXPLICIT_RATIO_DEFAULT);
            assertThat(ProvenanceInspector.marker(client.environment()).version()).isEqualTo("remote-v3-after");
            recordRefresh("T3", client, after, eventKeys.get(), rebuiltRatio);
        }
    }

    @Test
    void t4_equalStructuralBootstrapAndRemoteValuesDoNotRequireRestart() throws Exception {
        withSystemProperty(STRUCTURAL_QUEUE, "2048", () -> {
            try (ConfigServerResponseStub server = server(
                    Map.of(STRUCTURAL_QUEUE, "2048"),
                    "remote-v4",
                    "state-v4"
            ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
                KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), STRUCTURAL_QUEUE);
                boolean restartRequired = !String.valueOf(evidence.effectiveValue())
                        .equals(String.valueOf(evidence.remoteDesiredValue()));

                assertThat(evidence.effectiveCategory()).isEqualTo(ProvenanceCategory.SYSTEM);
                assertThat(evidence.remoteDesiredValue()).isEqualTo("2048");
                assertThat(evidence.localOverride()).isTrue();
                assertThat(evidence.conflict()).isFalse();
                assertThat(restartRequired).isFalse();
                recordStructural("T4", client, evidence, restartRequired);
            }
        });
    }

    @Test
    void t5_differentStructuralDesiredValueIsVisibleAndRequiresRestart() throws Exception {
        withSystemProperty(STRUCTURAL_QUEUE, "2048", () -> {
            try (ConfigServerResponseStub server = server(
                    Map.of(STRUCTURAL_QUEUE, "4096"),
                    "remote-v5",
                    "state-v5"
            ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
                KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), STRUCTURAL_QUEUE);
                boolean restartRequired = !String.valueOf(evidence.effectiveValue())
                        .equals(String.valueOf(evidence.remoteDesiredValue()));

                assertThat(evidence.effectiveValue()).isEqualTo("2048");
                assertThat(evidence.remoteDesiredValue()).isEqualTo("4096");
                assertThat(evidence.localOverride()).isTrue();
                assertThat(evidence.conflict()).isTrue();
                assertThat(restartRequired).isTrue();
                recordStructural("T5", client, evidence, restartRequired);
            }
        });
    }

    @Test
    void t6_systemPolicyOverrideIsDetectedAndFailsClosed() throws Exception {
        withSystemProperty(POLICY_RATIO, "0.99", () -> {
            try (ConfigServerResponseStub server = server(
                    Map.of(POLICY_RATIO, "0.20"),
                    "remote-v6",
                    "state-v6"
            ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
                KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
                boolean guardAllows = evidence.effectiveCategory() == ProvenanceCategory.CONFIG_SERVER_REMOTE
                        && !evidence.localOverride();

                assertThat(evidence.effectiveCategory()).isEqualTo(ProvenanceCategory.SYSTEM);
                assertThat(evidence.remoteDesiredValue()).isEqualTo("0.20");
                assertThat(evidence.localOverride()).isTrue();
                assertThat(evidence.conflict()).isTrue();
                assertThat(guardAllows).isFalse();
                record("T6", client, evidence, ProvenanceInspector.marker(client.environment()), POLICY_RATIO);
            }
        });
    }

    @Test
    void t7_successfulFetchWithoutVersionKeepsRevisionOptional() throws Exception {
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "0.70"),
                null,
                "state-v7"
        ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
            MarkerEvidence marker = ProvenanceInspector.marker(client.environment());
            KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);

            assertThat(marker.present()).isTrue();
            assertThat(marker.version()).isNull();
            assertThat(marker.state()).isEqualTo("state-v7");
            assertThat(evidence.effectiveCategory()).isEqualTo(ProvenanceCategory.CONFIG_SERVER_REMOTE);
            assertThat(evidence.remoteDesiredValue()).isEqualTo("0.70");
            record("T7", client, evidence, marker, POLICY_RATIO);
        }
    }

    @Test
    void t8_directMarkerReadDefeatsEffectiveVersionSpoofing() throws Exception {
        withSystemProperty("config.client.version", "fake-local-version", () -> {
            try (ConfigServerResponseStub server = server(
                    Map.of(POLICY_RATIO, "0.80"),
                    "actual-remote-version",
                    "state-v8"
            ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
                MarkerEvidence marker = ProvenanceInspector.marker(client.environment());

                assertThat(client.environment().getProperty("config.client.version"))
                        .isEqualTo("fake-local-version");
                assertThat(marker.version()).isEqualTo("actual-remote-version");
                KeyEvidence evidence = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
                record("T8", client, evidence, marker, POLICY_RATIO, "config.client.version");
            }
        });
    }

    @Test
    void t9_removedPropertyAppearsInDiffAndDisappearsFromBothViews() throws Exception {
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "0.90", POLICY_ROUTE, "0.45"),
                "remote-v9-before",
                "state-v9-before"
        ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
            AtomicReference<Set<String>> eventKeys = captureEventKeys(client);
            server.respond(Map.of(POLICY_ROUTE, "0.45"), "remote-v9-after", "state-v9-after");

            refresh(client);
            KeyEvidence removed = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
            Map<String, String> fullSnapshot = Map.of(
                    POLICY_RATIO,
                    removed.effectiveValue() == null
                            ? EXPLICIT_RATIO_DEFAULT
                            : String.valueOf(removed.effectiveValue())
            );

            assertThat(eventKeys.get()).contains(POLICY_RATIO);
            assertThat(client.environment().getProperty(POLICY_RATIO)).isNull();
            assertThat(removed.effectiveCategory()).isEqualTo(ProvenanceCategory.ABSENT);
            assertThat(removed.remoteDesiredValue()).isNull();
            assertThat(fullSnapshot.get(POLICY_RATIO)).isEqualTo(EXPLICIT_RATIO_DEFAULT);
            recordRefresh("T9", client, removed, eventKeys.get(), fullSnapshot.toString());
        }
    }

    @Test
    void t10_listenerSeesUpdatedEnvironmentSourcesAndMarker() throws Exception {
        try (ConfigServerResponseStub server = server(
                Map.of(POLICY_RATIO, "0.11"),
                "remote-v10-before",
                "state-v10-before"
        ); ConfigClientHarness client = ConfigClientHarness.start(server.uri())) {
            AtomicReference<EventObservation> observation = new AtomicReference<>();
            client.context().addApplicationListener(event -> {
                if (event instanceof EnvironmentChangeEvent changeEvent) {
                    KeyEvidence current = ProvenanceInspector.inspect(client.environment(), POLICY_RATIO);
                    observation.set(new EventObservation(
                            changeEvent.getKeys(),
                            current,
                            ProvenanceInspector.marker(client.environment())
                    ));
                }
            });
            server.respond(Map.of(POLICY_RATIO, "0.22"), "remote-v10-after", "state-v10-after");

            refresh(client);
            EventObservation duringEvent = observation.get();

            assertThat(duringEvent).isNotNull();
            assertThat(duringEvent.keys()).contains(POLICY_RATIO);
            assertThat(duringEvent.evidence().effectiveValue()).isEqualTo("0.22");
            assertThat(duringEvent.evidence().remoteDesiredValue()).isEqualTo("0.22");
            assertThat(duringEvent.marker().version()).isEqualTo("remote-v10-after");
            assertThat(duringEvent.evidence().effectiveSource().name()).contains("configserver:");
            recordRefresh("T10", client, duringEvent.evidence(), duringEvent.keys(), duringEvent.toString());
        }
    }

    private static ConfigServerResponseStub server(
            Map<String, Object> properties,
            String version,
            String state
    ) throws Exception {
        ConfigServerResponseStub server = new ConfigServerResponseStub();
        server.respond(properties, version, state);
        return server;
    }

    private static Set<String> refresh(ConfigClientHarness client) {
        return client.context().getBean(ContextRefresher.class).refresh();
    }

    private static AtomicReference<Set<String>> captureEventKeys(ConfigClientHarness client) {
        AtomicReference<Set<String>> eventKeys = new AtomicReference<>(Set.of());
        client.context().addApplicationListener(event -> {
            if (event instanceof EnvironmentChangeEvent changeEvent) {
                eventKeys.set(Set.copyOf(changeEvent.getKeys()));
            }
        });
        return eventKeys;
    }

    private static void record(
            String scenario,
            ConfigClientHarness client,
            KeyEvidence evidence,
            MarkerEvidence marker,
            String... keys
    ) {
        EvidenceRecorder.record(
                scenario,
                evidence,
                marker,
                ProvenanceInspector.sanitizedTree(client.environment(), keys),
                "guard=" + (evidence.localOverride() ? "FAIL_CLOSED" : "ACCEPT")
        );
    }

    private static void recordRefresh(
            String scenario,
            ConfigClientHarness client,
            KeyEvidence evidence,
            Set<String> eventKeys,
            String rebuilt
    ) {
        EvidenceRecorder.record(
                scenario,
                evidence,
                ProvenanceInspector.marker(client.environment()),
                ProvenanceInspector.sanitizedTree(client.environment(), POLICY_RATIO),
                "eventKeys=" + eventKeys + "; rebuiltFullSnapshot=" + rebuilt
        );
    }

    private static void recordStructural(
            String scenario,
            ConfigClientHarness client,
            KeyEvidence evidence,
            boolean restartRequired
    ) {
        EvidenceRecorder.record(
                scenario,
                evidence,
                ProvenanceInspector.marker(client.environment()),
                ProvenanceInspector.sanitizedTree(client.environment(), STRUCTURAL_QUEUE),
                "runtimePolicy=UNCHANGED; restartRequired=" + restartRequired
        );
    }

    private static int indexOfSource(List<String> tree, String sourceFragment) {
        for (int index = 0; index < tree.size(); index++) {
            if (tree.get(index).contains(sourceFragment)) {
                return index;
            }
        }
        return -1;
    }

    private static void withSystemProperty(String key, String value, ThrowingAction action) throws Exception {
        synchronized (SYSTEM_PROPERTIES_MONITOR) {
            String previous = System.getProperty(key);
            System.setProperty(key, value);
            try {
                action.run();
            } finally {
                if (previous == null) {
                    System.clearProperty(key);
                } else {
                    System.setProperty(key, previous);
                }
            }
        }
    }

    @FunctionalInterface
    private interface ThrowingAction {
        void run() throws Exception;
    }

    private record EventObservation(
            Set<String> keys,
            KeyEvidence evidence,
            MarkerEvidence marker
    ) {
    }
}
