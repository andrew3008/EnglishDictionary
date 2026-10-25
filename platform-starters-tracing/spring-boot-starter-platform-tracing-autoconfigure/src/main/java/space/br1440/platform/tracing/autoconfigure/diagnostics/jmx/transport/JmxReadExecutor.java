package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import java.io.IOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.IntrospectionException;
import javax.management.MBeanException;
import javax.management.MBeanInfo;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.RuntimeOperationsException;

public final class JmxReadExecutor {

    private static final Duration DEFAULT_WARN_RATE_LIMIT = Duration.ofMinutes(5);
    private static final Consumer<String> NOOP_AGENT_INSTANCE_ID_VERIFIER = ignored -> {
    };

    private final MBeanServerConnection connection;
    private final Clock clock;
    private final JmxReadStateRegistry registry;
    private final JmxWireGraphValidator wireValidator;
    private final Consumer<String> agentInstanceIdVerifier;
    private final EnumMap<JmxDomain, ReentrantLock> transportLocks = new EnumMap<>(JmxDomain.class);
    private final AtomicLong observationSequence = new AtomicLong();

    JmxReadExecutor(MBeanServerConnection connection) {
        this(connection, NOOP_AGENT_INSTANCE_ID_VERIFIER);
    }

    public JmxReadExecutor(MBeanServerConnection connection,
                           Consumer<String> agentInstanceIdVerifier) {
        this(connection, Clock.systemUTC(), DEFAULT_WARN_RATE_LIMIT, agentInstanceIdVerifier);
    }

    JmxReadExecutor(MBeanServerConnection connection, Clock clock, Duration warnRateLimit) {
        this(connection, clock, warnRateLimit, NOOP_AGENT_INSTANCE_ID_VERIFIER);
    }

    JmxReadExecutor(MBeanServerConnection connection,
                    Clock clock,
                    Duration warnRateLimit,
                    Consumer<String> agentInstanceIdVerifier) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = new JmxReadStateRegistry(clock, warnRateLimit);
        this.wireValidator = new JmxWireGraphValidator();
        this.agentInstanceIdVerifier = Objects.requireNonNull(
                agentInstanceIdVerifier,
                "agentInstanceIdVerifier"
        );

        for (JmxDomain domain : JmxDomain.values()) {
            transportLocks.put(domain, new ReentrantLock());
        }
    }

    public List<JmxDiagnosticSnapshot> diagnosticSnapshot() {
        return registry.diagnosticSnapshot();
    }

    public Optional<Duration> lastSuccessAge(JmxContractPoint point) {
        return registry.lastSuccessAge(point);
    }

    public <T> Optional<T> read(ObjectName objectName, JmxAttribute<T> attribute) {
        Objects.requireNonNull(objectName, "objectName");
        Objects.requireNonNull(attribute, "attribute");
        TimedRead raw = readRaw(
                attribute.point(),
                () -> readExactAttribute(objectName, attribute));
        return resolve(attribute, raw);
    }

    public JmxMBeanRegistrationState registration(ObjectName objectName, JmxContractPoint point) {
        ReentrantLock lock = transportLocks.get(point.domain());
        lock.lock();

        try {
            boolean registered;
            long sequence;
            Instant observedAt;

            try {
                registered = connection.isRegistered(objectName);
                sequence = observationSequence.incrementAndGet();
                observedAt = clock.instant();
            } catch (IOException e) {
                sequence = observationSequence.incrementAndGet();
                observedAt = clock.instant();
                registry.recordExecutionFailure(point, JmxDiagnosticKind.CONNECTOR_FAILURE, sequence, observedAt);
                return JmxMBeanRegistrationState.UNKNOWN;
            } catch (SecurityException e) {
                sequence = observationSequence.incrementAndGet();
                observedAt = clock.instant();
                registry.recordExecutionFailure(point, JmxDiagnosticKind.SECURITY_FAILURE, sequence, observedAt);
                return JmxMBeanRegistrationState.UNKNOWN;
            }

            if (registered) {
                registry.recordPresent(point, sequence, observedAt);
                return JmxMBeanRegistrationState.REGISTERED;
            }

            registry.recordAbsent(point, sequence, observedAt);
            return JmxMBeanRegistrationState.NOT_REGISTERED;
        } finally {
            lock.unlock();
        }
    }

    private <T> Optional<T> resolve(JmxAttribute<T> attribute, TimedRead raw) {
        JmxContractPoint point = attribute.point();
        return switch (raw.read()) {
            case JmxRead.Present present -> resolvePresent(
                    attribute,
                    raw.sequence(),
                    raw.observedAt(),
                    present.value()
            );

            case JmxRead.Absent ignored -> {
                registry.recordAbsent(point, raw.sequence(), raw.observedAt());
                yield Optional.empty();
            }

            case JmxRead.ContractViolation violation -> {
                registry.recordContractViolation(point, violation.kind(), raw.sequence(), raw.observedAt());
                yield Optional.empty();
            }

            case JmxRead.ExecutionFailure failure -> {
                registry.recordExecutionFailure(point, failure.kind(), raw.sequence(), raw.observedAt());
                yield Optional.empty();
            }
        };
    }

    private <T> Optional<T> resolvePresent(JmxAttribute<T> attribute,
                                           long sequence,
                                           Instant observedAt,
                                           Object rawValue) {
        JmxContractPoint point = attribute.point();
        if (rawValue == null) {
            registry.recordContractViolation(point, JmxDiagnosticKind.NULL_VALUE_FOR_REQUIRED_MEMBER, sequence, observedAt);
            return Optional.empty();
        }

        try {
            wireValidator.validate(rawValue);
            T decoded = attribute.decoder().decode(rawValue);
            String agentInstanceId = attribute.agentInstanceIdExtractor().apply(decoded);
            if (agentInstanceId != null) {
                agentInstanceIdVerifier.accept(agentInstanceId);
            }
            registry.recordPresent(point, sequence, observedAt);
            return Optional.of(decoded);
        } catch (JmxWireTypeViolationException ignored) {
            registry.recordContractViolation(point, JmxDiagnosticKind.JMX_WIRE_TYPE_VIOLATION, sequence, observedAt);
            return Optional.empty();
        } catch (JmxContractViolationException e) {
            registry.recordContractViolation(point, e.kind(), sequence, observedAt);
            return Optional.empty();
        }
    }

    private TimedRead readRaw(JmxContractPoint point, TransportCall call) {
        ReentrantLock lock = transportLocks.get(point.domain());
        lock.lock();

        try {
            try {
                Object value = call.execute();
                return new TimedRead(observationSequence.incrementAndGet(), clock.instant(), new JmxRead.Present(value));
            } catch (JmxMBeanInfoShapeViolationException e) {
                return contractViolation(JmxDiagnosticKind.MBEAN_INFO_SHAPE_VIOLATION);
            } catch (MetadataRetrievalException e) {
                return executionFailure(JmxDiagnosticKind.METADATA_RETRIEVAL_FAILURE);
            } catch (InstanceNotFoundException e) {
                return new TimedRead(observationSequence.incrementAndGet(), clock.instant(), new JmxRead.Absent());
            } catch (AttributeNotFoundException e) {
                return contractViolation(JmxDiagnosticKind.MISSING_ATTRIBUTE);
            } catch (ReflectionException e) {
                Throwable cause = e.getCause();
                if (cause instanceof NoSuchMethodException || cause instanceof ClassNotFoundException) {
                    return contractViolation(JmxDiagnosticKind.OPERATION_SIGNATURE_DRIFT);
                }

                return executionFailure(JmxDiagnosticKind.REFLECTION_UNCLASSIFIED);
            } catch (MBeanException | RuntimeMBeanException e) {
                return executionFailure(JmxDiagnosticKind.MBEAN_EXECUTION_FAILURE);
            } catch (RuntimeErrorException e) {
                return executionFailure(JmxDiagnosticKind.MBEAN_RUNTIME_ERROR);
            } catch (RuntimeOperationsException e) {
                if (e.getCause() instanceof IllegalArgumentException) {
                    throw new IllegalArgumentException("Locally invalid JMX operation call for " + point, e);
                }

                return executionFailure(JmxDiagnosticKind.OPERATIONS_EXCEPTION_UNCLASSIFIED);
            } catch (SecurityException e) {
                return executionFailure(JmxDiagnosticKind.SECURITY_FAILURE);
            } catch (IOException e) {
                return executionFailure(JmxDiagnosticKind.CONNECTOR_FAILURE);
            }
        } finally {
            lock.unlock();
        }
    }

    private Object readExactAttribute(
            ObjectName objectName,
            JmxAttribute<?> attribute)
            throws InstanceNotFoundException, AttributeNotFoundException, MBeanException,
            ReflectionException, IOException {
        MBeanInfo mBeanInfo;
        try {
            mBeanInfo = connection.getMBeanInfo(objectName);
        } catch (IntrospectionException | ReflectionException e) {
            throw new MetadataRetrievalException(e);
        }
        JmxMBeanInfoShape.requireReadOnlySnapshot(mBeanInfo, attribute.name(), attribute.openType());
        return connection.getAttribute(objectName, attribute.name());
    }

    private TimedRead contractViolation(JmxDiagnosticKind kind) {
        return new TimedRead(
                observationSequence.incrementAndGet(),
                clock.instant(),
                new JmxRead.ContractViolation(kind));
    }

    private TimedRead executionFailure(JmxDiagnosticKind kind) {
        return new TimedRead(observationSequence.incrementAndGet(), clock.instant(), new JmxRead.ExecutionFailure(kind));
    }

    @FunctionalInterface
    private interface TransportCall {
        Object execute() throws InstanceNotFoundException, AttributeNotFoundException,
                MBeanException, ReflectionException, IOException;
    }

    private static final class MetadataRetrievalException extends RuntimeException {

        private MetadataRetrievalException(Exception cause) {
            super("Cannot retrieve JMX MBean metadata", cause, false, false);
        }
    }

    private sealed interface JmxRead {

        record Present(Object value) implements JmxRead {
        }

        record Absent() implements JmxRead {
        }

        record ContractViolation(JmxDiagnosticKind kind) implements JmxRead {
        }

        record ExecutionFailure(JmxDiagnosticKind kind) implements JmxRead {
        }
    }

    private record TimedRead(long sequence, Instant observedAt, JmxRead read) {
    }
}
