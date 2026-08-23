package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.transport;

import javax.management.AttributeNotFoundException;
import javax.management.InstanceNotFoundException;
import javax.management.MBeanException;
import javax.management.MBeanServerConnection;
import javax.management.ObjectName;
import javax.management.ReflectionException;
import javax.management.RuntimeErrorException;
import javax.management.RuntimeMBeanException;
import javax.management.RuntimeOperationsException;
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

public final class JmxReadExecutor {

    private static final Duration DEFAULT_WARN_RATE_LIMIT = Duration.ofMinutes(5);

    private final MBeanServerConnection connection;
    private final Clock clock;
    private final JmxReadStateRegistry registry;
    private final EnumMap<JmxDomain, ReentrantLock> transportLocks = new EnumMap<>(JmxDomain.class);
    private final AtomicLong observationSequence = new AtomicLong();

    public JmxReadExecutor(MBeanServerConnection connection) {
        this(connection, Clock.systemUTC(), DEFAULT_WARN_RATE_LIMIT);
    }

    public JmxReadExecutor(MBeanServerConnection connection, Clock clock, Duration warnRateLimit) {
        this.connection = Objects.requireNonNull(connection, "connection");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.registry = new JmxReadStateRegistry(clock, warnRateLimit);

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
        TimedRead raw = readRaw(attribute.point(), () -> connection.getAttribute(objectName, attribute.name()));
        return resolve(attribute.point(), attribute.decoder(), raw);
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

    private <T> Optional<T> resolve(JmxContractPoint point, JmxValueDecoder<T> decoder, TimedRead raw) {
        return switch (raw.read()) {
            case JmxRead.Present present -> resolvePresent(point, decoder, raw.sequence(), raw.observedAt(), present.value());

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

    private <T> Optional<T> resolvePresent(JmxContractPoint point, JmxValueDecoder<T> decoder, long sequence, Instant observedAt, Object rawValue) {
        if (rawValue == null) {
            registry.recordContractViolation(point, JmxDiagnosticKind.NULL_VALUE_FOR_REQUIRED_MEMBER, sequence, observedAt);
            return Optional.empty();
        }

        try {
            T decoded = decoder.decode(rawValue);
            registry.recordPresent(point, sequence, observedAt);
            return Optional.of(decoded);
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

    private TimedRead contractViolation(JmxDiagnosticKind kind) {
        return new TimedRead(observationSequence.incrementAndGet(), clock.instant(), new JmxRead.ContractViolation(kind));
    }

    private TimedRead executionFailure(JmxDiagnosticKind kind) {
        return new TimedRead(observationSequence.incrementAndGet(), clock.instant(), new JmxRead.ExecutionFailure(kind));
    }

    @FunctionalInterface
    private interface TransportCall {
        Object execute() throws InstanceNotFoundException, AttributeNotFoundException, MBeanException, ReflectionException, IOException;
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
