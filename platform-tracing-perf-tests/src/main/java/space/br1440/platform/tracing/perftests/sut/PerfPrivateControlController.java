package space.br1440.platform.tracing.perftests.sut;

import java.lang.management.ManagementFactory;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.UnaryOperator;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.management.openmbean.CompositeData;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateControlLocator;
import space.br1440.platform.tracing.autoconfigure.control.jmx.PrivateJmxTracingPolicySnapshotClient;
import space.br1440.platform.tracing.autoconfigure.control.jmx.protocol.PrivateControlOpenTypeCodec;
import space.br1440.platform.tracing.autoconfigure.control.policy.read.AcceptedSnapshotWithSequence;
import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicyDeliveryResult;
import space.br1440.platform.tracing.autoconfigure.control.policy.delivery.TracingPolicySource;

/**
 * Внутренний HTTP-fixture перф-стенда для M10/M10c/M10d.
 *
 * <p>Контроллер не обращается к legacy platform MBean setters. Каждая команда читает полный
 * accepted snapshot из private process-local MBeanServer, меняет ровно один Category A leaf и
 * отправляет полный snapshot через {@code applySnapshot}. Инициализация выполняется лениво,
 * чтобы сценарии без Agent не зависели от private control plane.
 */
@RestController
public final class PerfPrivateControlController {

    private static final String EXPORT_OBJECT_NAME =
            "space.br1440.platform.tracing:type=ExportControl,name=PlatformExportControl";
    private static final String DIAGNOSTICS_OBJECT_NAME =
            "space.br1440.platform.tracing:type=DiagnosticsControl,name=PlatformDiagnosticsControl";

    private final PrivateControlOpenTypeCodec codec = new PrivateControlOpenTypeCodec();
    private final AtomicLong rejectedCount = new AtomicLong();

    private volatile PrivateJmxTracingPolicySnapshotClient client;

    @PostMapping("/perf/private-control/sampling-ratio")
    public ResponseEntity<Map<String, Object>> setSamplingRatio(
            @RequestParam("value") double value) {
        return apply(snapshot -> snapshot.withSamplingRatio(value));
    }

    @PostMapping("/perf/private-control/exporter")
    public ResponseEntity<Map<String, Object>> setExporterEnabled(
            @RequestParam("enabled") boolean enabled) {
        return apply(snapshot -> snapshot.withExporterEnabled(enabled));
    }

    @PostMapping("/perf/private-control/trace-control-headers")
    public ResponseEntity<Map<String, Object>> setTraceControlHeadersEnabled(
            @RequestParam("enabled") boolean enabled) {
        return apply(snapshot -> snapshot.withTraceControlHeadersEnabled(enabled));
    }

    @GetMapping("/perf/private-control/state")
    public ResponseEntity<Map<String, Object>> acceptedState() {
        try {
            Optional<AcceptedSnapshotWithSequence> current = client().currentComplete();
            if (current.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("privateControlAvailable", false));
            }
            return ResponseEntity.ok(stateBody(current.get()));
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "privateControlAvailable", false,
                            "failure", exception.getClass().getSimpleName()));
        }
    }

    /**
     * Read-only queue diagnostics remain on the approved public projection. Mutation never uses
     * these platform MBeans.
     */
    @GetMapping("/perf/private-control/export-metrics")
    public ResponseEntity<Map<String, Object>> exportMetrics() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName exportName = new ObjectName(EXPORT_OBJECT_NAME);
            ObjectName diagnosticsName = new ObjectName(DIAGNOSTICS_OBJECT_NAME);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("queueSize", server.getAttribute(exportName, "ExportQueueSize"));
            result.put("queueCapacity", server.getAttribute(exportName, "ExportQueueCapacity"));
            result.put("droppedOverflow", server.getAttribute(
                    exportName,
                    "ExportDroppedOverflowTotal"));
            result.put("exportFailures", server.getAttribute(exportName, "ExportFailuresTotal"));
            result.put("exportTimeouts", server.getAttribute(exportName, "ExportTimeoutsTotal"));
            result.put("configReload", server.getAttribute(
                    diagnosticsName,
                    "ConfigReloadMetrics"));
            return ResponseEntity.ok(result);
        } catch (InstanceNotFoundException exception) {
            return ResponseEntity.ok(Map.of("extensionRegistered", false));
        } catch (Exception exception) {
            return ResponseEntity.internalServerError()
                    .body(Map.of("failure", exception.getClass().getSimpleName()));
        }
    }

    private synchronized ResponseEntity<Map<String, Object>> apply(
            UnaryOperator<CompleteTracingPolicySnapshot> change) {
        try {
            PrivateJmxTracingPolicySnapshotClient snapshotClient = client();
            Optional<AcceptedSnapshotWithSequence> current = snapshotClient.currentComplete();
            if (current.isEmpty()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("outcome", TracingPolicyDeliveryResult.FAILED.name()));
            }

            AcceptedSnapshotWithSequence before = current.get();
            CompleteTracingPolicySnapshot candidate = change.apply(before.snapshot());
            String candidateDigest = digest(
                    before.runtimeInstanceId(),
                    before.acceptedSequence(),
                    candidate);
            TracingPolicyDeliveryResult outcome = snapshotClient.applySnapshot(
                    before.acceptedSequence(),
                    candidate,
                    TracingPolicySource.ACTUATOR);
            if (outcome == TracingPolicyDeliveryResult.REJECTED) {
                rejectedCount.incrementAndGet();
            }

            Optional<AcceptedSnapshotWithSequence> reread = snapshotClient.currentComplete();
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("outcome", outcome.name());
            body.put("candidateDigest", candidateDigest);
            if (reread.isPresent()) {
                AcceptedSnapshotWithSequence after = reread.get();
                long expectedSequence = outcome == TracingPolicyDeliveryResult.APPLIED
                        ? before.acceptedSequence() + 1L
                        : before.acceptedSequence();
                String acceptedDigest = digest(
                        after.runtimeInstanceId(),
                        after.acceptedSequence(),
                        after.snapshot());
                body.putAll(stateBody(after));
                body.put("sequenceValid", after.acceptedSequence() == expectedSequence);
                body.put("digestMatchesCandidate", outcome != TracingPolicyDeliveryResult.APPLIED
                        || candidateDigest.equals(acceptedDigest));
            } else {
                body.put("sequenceValid", false);
                body.put("digestMatchesCandidate", false);
            }
            return ResponseEntity.status(status(outcome)).body(body);
        } catch (RuntimeException exception) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "outcome", TracingPolicyDeliveryResult.FAILED.name(),
                            "failure", exception.getClass().getSimpleName()));
        }
    }

    private PrivateJmxTracingPolicySnapshotClient client() {
        PrivateJmxTracingPolicySnapshotClient current = client;
        if (current != null) {
            return current;
        }
        synchronized (this) {
            current = client;
            if (current == null) {
                current = new PrivateJmxTracingPolicySnapshotClient(new PrivateControlLocator());
                current.initialize();
                client = current;
            }
            return current;
        }
    }

    private Map<String, Object> stateBody(AcceptedSnapshotWithSequence accepted) {
        CompleteTracingPolicySnapshot snapshot = accepted.snapshot();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("privateControlAvailable", true);
        body.put("runtimeInstanceId", accepted.runtimeInstanceId());
        body.put("acceptedSequence", accepted.acceptedSequence());
        body.put("payloadDigest", digest(
                accepted.runtimeInstanceId(),
                accepted.acceptedSequence(),
                snapshot));
        body.put("samplingRatio", snapshot.samplingRatio());
        body.put("exporterEnabled", snapshot.exporterEnabled());
        body.put("traceControlHeadersEnabled", snapshot.traceControlHeadersEnabled());
        body.put("rejectedCount", rejectedCount.get());
        return body;
    }

    private String digest(
            String runtimeInstanceId,
            long sequence,
            CompleteTracingPolicySnapshot snapshot) {
        CompositeData request = codec.encodeApplyRequest(
                runtimeInstanceId,
                sequence,
                UUID.randomUUID().toString(),
                "DEV_ACTUATOR",
                "DEVELOPER_COMMAND",
                "perf-fixture",
                snapshot);
        return (String) request.get("candidateDigest");
    }

    private static HttpStatus status(TracingPolicyDeliveryResult outcome) {
        return switch (outcome) {
            case APPLIED, UNCHANGED -> HttpStatus.OK;
            case REJECTED -> HttpStatus.UNPROCESSABLE_ENTITY;
            case STALE -> HttpStatus.CONFLICT;
            case FAILED -> HttpStatus.SERVICE_UNAVAILABLE;
        };
    }
}
