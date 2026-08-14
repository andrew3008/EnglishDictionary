package space.br1440.platform.tracing.otel.javaagent.control.jmx.lifecycle;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import javax.management.MBeanServer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import space.br1440.platform.tracing.otel.javaagent.control.TracingSettingsSource;
import space.br1440.platform.tracing.otel.javaagent.control.jmx.protocol.PrivateControlWriterMBean;
import space.br1440.platform.tracing.otel.javaagent.control.settings.TracingSettingsStore;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.LastSettingsUpdate;
import space.br1440.platform.tracing.otel.javaagent.control.settings.update.TracingSettingsUpdater;

public final class PrivateControlPlane implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(PrivateControlPlane.class);

    private static final String STATE_MARKER_REGISTERED = "MARKER_REGISTERED";
    private static final String STATE_CODEC_READY = "CODEC_READY";
    private static final String STATE_WRITER_READY = "WRITER_READY";
    private static final String STATE_FAILED = "FAILED";
    private static final String STATE_STOPPED = "STOPPED";

    public static AutoCloseable activate(TracingSettingsSource source) {
        Objects.requireNonNull(source, "source");

        PrivateControlPlane plane = new PrivateControlPlane(
                source.current().runtimeInstanceId(),
                source.store(),
                source.updater(),
                source.diagnostics()
        );
        plane.start();
        return plane;
    }

    private final String runtimeInstanceId;
    private final TracingSettingsStore store;
    private final TracingSettingsUpdater updater;
    private final LastSettingsUpdate diagnostics;

    private PrivateControlMarkerMBean marker;
    private PrivateControlServer privateServer;
    private PlatformSettingsStatusRegistration statusRegistration;

    PrivateControlPlane(String runtimeInstanceId,
                        TracingSettingsStore store,
                        TracingSettingsUpdater updater,
                        LastSettingsUpdate diagnostics) {
        this.runtimeInstanceId = Objects.requireNonNull(runtimeInstanceId, "runtimeInstanceId");
        this.store = Objects.requireNonNull(store, "store");
        this.updater = Objects.requireNonNull(updater, "updater");
        this.diagnostics = Objects.requireNonNull(diagnostics, "diagnostics");
    }

    synchronized MBeanServer start() {
        if (privateServer != null) {
            return privateServer.server();
        }

        PrivateControlMarkerMBean candidateMarker = new PrivateControlMarkerMBean(runtimeInstanceId);
        PrivateControlServer candidateServer = new PrivateControlServer();

        try {
            candidateServer.registerMarker(candidateMarker);
            candidateMarker.lifecycleState(STATE_MARKER_REGISTERED);

            PrivateControlWriterMBean writer = new PrivateControlWriterMBean(updater, store);
            candidateMarker.lifecycleState(STATE_CODEC_READY);
            candidateServer.registerWriter(writer);
            candidateMarker.lifecycleState(STATE_WRITER_READY);

            statusRegistration = new PlatformSettingsStatusRegistration(store, diagnostics);

            this.marker = candidateMarker;
            this.privateServer = candidateServer;
            return privateServer.server();

        } catch (Exception exception) {
            log.error("Failed to initialize Private Control Plane MBeanServer. Rolling back registration.", exception);
            candidateMarker.lifecycleState(STATE_FAILED);

            IllegalStateException startFailure = new IllegalStateException("Private control MBeanServer initialization failed", exception);
            for (Exception cleanupFailure : candidateServer.close()) {
                log.warn("Suppressed cleanup failure during rollback", cleanupFailure);
                startFailure.addSuppressed(cleanupFailure);
            }
            throw startFailure;
        }
    }

    @Override
    public synchronized void close() {
        if (privateServer == null) {
            return;
        }

        List<Exception> failures = new ArrayList<>();

        if (statusRegistration != null) {
            Exception statusFailure = statusRegistration.close();
            if (statusFailure != null) {
                failures.add(statusFailure);
            }
        }

        failures.addAll(privateServer.close());

        if (marker != null) {
            try {
                marker.lifecycleState(STATE_STOPPED);
            } catch (Exception e) {
                log.warn("Failed to update marker state to STOPPED during shutdown", e);
            }
        }

        this.privateServer = null;
        this.marker = null;
        this.statusRegistration = null;

        if (!failures.isEmpty()) {
            Exception rootCause = failures.getFirst();
            log.error("Private Control Plane MBean cleanup encountered {} failures. Root cause attached.", failures.size(), rootCause);

            IllegalStateException cleanupFailure = new IllegalStateException("Private control MBean cleanup failed", rootCause);
            failures.stream().skip(1).forEach(cleanupFailure::addSuppressed);
            throw cleanupFailure;
        }
    }
}
