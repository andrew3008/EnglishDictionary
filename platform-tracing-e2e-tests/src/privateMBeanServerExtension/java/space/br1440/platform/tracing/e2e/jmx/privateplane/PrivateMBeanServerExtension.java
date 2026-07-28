package space.br1440.platform.tracing.e2e.jmx.privateplane;

import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizer;
import io.opentelemetry.sdk.autoconfigure.spi.AutoConfigurationCustomizerProvider;

import javax.management.MBeanServer;
import javax.management.MBeanServerFactory;
import javax.management.ObjectName;
import javax.management.StandardMBean;
import javax.management.openmbean.CompositeData;
import javax.management.openmbean.CompositeDataSupport;
import javax.management.openmbean.CompositeType;
import javax.management.openmbean.OpenDataException;
import javax.management.openmbean.OpenType;
import javax.management.openmbean.SimpleType;
import java.lang.management.ManagementFactory;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test-only Agent extension для S-03. В production-артефакты не входит.
 */
public final class PrivateMBeanServerExtension implements AutoConfigurationCustomizerProvider {

    static final String MARKER_NAME =
            "space.br1440.platform.tracing.private:type=Marker,name=RuntimeControl";
    static final String WRITER_NAME =
            "space.br1440.platform.tracing.private:type=Control,name=RuntimePolicyWriter";
    static final String PROJECTION_NAME =
            "space.br1440.platform.tracing:type=SpikeProjection,name=PrivateControlReadModel";
    static final String PROTOCOL_ID = "platform-tracing-private-control";
    static final int PROTOCOL_VERSION = 1;
    static final String MARKER_SIGNATURE = "S03_PRIVATE_PROCESS_LOCAL_V1";

    private static final Owner OWNER = new Owner();

    @Override
    public void customize(AutoConfigurationCustomizer customizer) {
        OWNER.initialize();
        // N1: повторная инициализация обязана вернуть существующего владельца без второго сервера.
        OWNER.initialize();
    }

    public interface MarkerMBean {

        String getProtocolId();

        int getProtocolVersion();

        String getRuntimeInstanceId();

        String getWriterObjectName();

        String getLifecycleState();

        String getMarkerSignature();
    }

    public interface WriterMBean {

        CompositeData apply(CompositeData request);

        void shutdown();
    }

    public interface ReadProjectionMBean {

        String getStatus();

        String getRuntimeInstanceId();

        long getSequence();
    }

    private static final class Owner {

        private final AtomicBoolean initialized = new AtomicBoolean();
        private final AtomicLong sequence = new AtomicLong();
        private final String runtimeInstanceId = UUID.randomUUID().toString();
        private final Marker marker = new Marker(runtimeInstanceId);
        private final Writer writer = new Writer(this, runtimeInstanceId, sequence);

        private volatile MBeanServer privateServer;

        void initialize() {
            if (!initialized.compareAndSet(false, true)) {
                emit("duplicateInitialization=existing-owner");
                return;
            }
            try {
                emit("event=server-created:begin");
                privateServer = MBeanServerFactory.createMBeanServer(
                        "space.br1440.platform.tracing.private");
                emit("event=server-created");

                privateServer.registerMBean(
                        new StandardMBean(marker, MarkerMBean.class, false),
                        new ObjectName(MARKER_NAME));
                marker.lifecycleState = "MARKER_REGISTERED";
                emit("event=marker-registered");

                WireTypes.initialize();
                marker.lifecycleState = "CODEC_READY";
                emit("event=codec-ready");

                privateServer.registerMBean(
                        new StandardMBean(writer, WriterMBean.class, false),
                        new ObjectName(WRITER_NAME));
                emit("event=writer-registered");

                MBeanServer platform = ManagementFactory.getPlatformMBeanServer();
                ObjectName projectionName = new ObjectName(PROJECTION_NAME);
                if (platform.isRegistered(projectionName)) {
                    platform.unregisterMBean(projectionName);
                }
                platform.registerMBean(
                        new StandardMBean(
                                new ReadProjection(runtimeInstanceId, sequence),
                                ReadProjectionMBean.class,
                                false),
                        projectionName);
                emit("event=state-store-ready");

                marker.lifecycleState = "WRITER_READY";
                emit("event=capability-visible");
                emit("extensionClassLoader=" + loaderName(getClass().getClassLoader()));
                Runtime.getRuntime().addShutdownHook(
                        new Thread(this::closeQuietly, "s03-private-mbeanserver-cleanup"));
            } catch (Exception failure) {
                marker.lifecycleState = "FAILED";
                closeQuietly();
                throw new IllegalStateException("S-03 private MBeanServer initialization failed", failure);
            }
        }

        synchronized void close() {
            MBeanServer server = privateServer;
            if (server == null) {
                return;
            }
            unregister(server, WRITER_NAME);
            emit("event=writer-unregistered");
            unregister(server, MARKER_NAME);
            emit("event=marker-unregistered");
            unregister(ManagementFactory.getPlatformMBeanServer(), PROJECTION_NAME);
            MBeanServerFactory.releaseMBeanServer(server);
            privateServer = null;
            marker.lifecycleState = "STOPPED";
            emit("event=server-released");
        }

        private void closeQuietly() {
            try {
                close();
            } catch (RuntimeException failure) {
                emit("cleanupFailure=" + failure.getClass().getName());
            }
        }

        private static void unregister(MBeanServer server, String objectName) {
            try {
                ObjectName name = new ObjectName(objectName);
                if (server.isRegistered(name)) {
                    server.unregisterMBean(name);
                }
            } catch (Exception failure) {
                throw new IllegalStateException("Cannot unregister " + objectName, failure);
            }
        }
    }

    private static final class Marker implements MarkerMBean {

        private final String runtimeInstanceId;
        private volatile String lifecycleState = "CREATED";

        private Marker(String runtimeInstanceId) {
            this.runtimeInstanceId = runtimeInstanceId;
        }

        @Override
        public String getProtocolId() {
            return PROTOCOL_ID;
        }

        @Override
        public int getProtocolVersion() {
            return PROTOCOL_VERSION;
        }

        @Override
        public String getRuntimeInstanceId() {
            return runtimeInstanceId;
        }

        @Override
        public String getWriterObjectName() {
            return WRITER_NAME;
        }

        @Override
        public String getLifecycleState() {
            return lifecycleState;
        }

        @Override
        public String getMarkerSignature() {
            return MARKER_SIGNATURE;
        }
    }

    private static final class Writer implements WriterMBean {

        private final Owner owner;
        private final String runtimeInstanceId;
        private final AtomicLong sequence;
        private volatile boolean enabled;

        private Writer(Owner owner, String runtimeInstanceId, AtomicLong sequence) {
            this.owner = owner;
            this.runtimeInstanceId = runtimeInstanceId;
            this.sequence = sequence;
        }

        @Override
        public synchronized CompositeData apply(CompositeData request) {
            if (request == null || !WireTypes.REQUEST.equals(request.getCompositeType())) {
                return response("REJECTED", "invalid composite type");
            }
            try {
                int version = (Integer) request.get("protocolVersion");
                String expectedInstance = (String) request.get("expectedRuntimeInstanceId");
                long expectedSequence = (Long) request.get("expectedSequence");
                String digest = (String) request.get("candidateDigest");
                boolean candidateEnabled = (Boolean) request.get("candidateEnabled");

                if (version != PROTOCOL_VERSION) {
                    return response("REJECTED", "protocol mismatch");
                }
                if (!runtimeInstanceId.equals(expectedInstance)) {
                    return response("STALE", "runtime instance mismatch");
                }
                if (sequence.get() != expectedSequence) {
                    return response("STALE", "sequence mismatch");
                }
                if (digest == null || digest.isBlank() || digest.length() > 128) {
                    return response("REJECTED", "candidate digest rejected");
                }
                enabled = candidateEnabled;
                sequence.incrementAndGet();
                return response("APPLIED", "applied");
            } catch (RuntimeException failure) {
                return response("FAILED", failure.getClass().getSimpleName());
            }
        }

        @Override
        public void shutdown() {
            owner.close();
        }

        private CompositeData response(String status, String message) {
            try {
                return new CompositeDataSupport(
                        WireTypes.RESPONSE,
                        WireTypes.RESPONSE_KEYS,
                        new Object[]{status, runtimeInstanceId, sequence.get(), message, enabled});
            } catch (OpenDataException failure) {
                throw new IllegalStateException("Cannot encode S-03 response", failure);
            }
        }
    }

    private record ReadProjection(String runtimeInstanceId, AtomicLong sequence)
            implements ReadProjectionMBean {

        @Override
        public String getStatus() {
            return "READ_ONLY";
        }

        @Override
        public String getRuntimeInstanceId() {
            return runtimeInstanceId;
        }

        @Override
        public long getSequence() {
            return sequence.get();
        }
    }

    private static final class WireTypes {

        private static final String[] REQUEST_KEYS = {
                "protocolVersion",
                "expectedRuntimeInstanceId",
                "expectedSequence",
                "candidateDigest",
                "candidateEnabled"
        };
        private static final String[] RESPONSE_KEYS = {
                "status",
                "runtimeInstanceId",
                "sequence",
                "message",
                "enabled"
        };
        private static final CompositeType REQUEST = composite(
                "PlatformTracingPrivateRequestV1",
                REQUEST_KEYS,
                new OpenType<?>[]{
                        SimpleType.INTEGER,
                        SimpleType.STRING,
                        SimpleType.LONG,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN
                });
        private static final CompositeType RESPONSE = composite(
                "PlatformTracingPrivateResponseV1",
                RESPONSE_KEYS,
                new OpenType<?>[]{
                        SimpleType.STRING,
                        SimpleType.STRING,
                        SimpleType.LONG,
                        SimpleType.STRING,
                        SimpleType.BOOLEAN
                });

        private WireTypes() {
        }

        static void initialize() {
            if (!Set.of(REQUEST_KEYS).equals(REQUEST.keySet())) {
                throw new IllegalStateException("Unexpected request CompositeType");
            }
        }

        private static CompositeType composite(
                String name,
                String[] keys,
                OpenType<?>[] types) {
            try {
                return new CompositeType(name, name, keys, keys, types);
            } catch (OpenDataException failure) {
                throw new ExceptionInInitializerError(failure);
            }
        }
    }

    private static String loaderName(ClassLoader loader) {
        return loader == null ? "bootstrap" : loader.getClass().getName();
    }

    private static void emit(String value) {
        System.out.println("S03_PRIVATE:" + value);
        System.out.flush();
    }
}
