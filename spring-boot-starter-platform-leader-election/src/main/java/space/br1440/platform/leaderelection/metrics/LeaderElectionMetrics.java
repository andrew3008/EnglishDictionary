package space.br1440.platform.leaderelection.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

@RequiredArgsConstructor
public class LeaderElectionMetrics implements LeaderElectionMetricsInterface {

    private final MeterRegistry meterRegistry;
    private final ConcurrentHashMap<String, AtomicInteger> leadershipStatus = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> leadershipStartTime = new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, AtomicLong> leaseAcquireTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicLong> leaseRenewTime = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> leaseDurationSeconds = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, AtomicInteger> leaseTransitions = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> leaseHolderIdentity = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, String> leasePodName = new ConcurrentHashMap<>();

    public static final String METRIC_PREFIX = "platform.leader.election";
    private static final String METRIC_IS_LEADER = METRIC_PREFIX + ".is_leader";
    private static final String METRIC_FAILED_ATTEMPTS = METRIC_PREFIX + ".failed.attempts";

    @Override
    public void registerLeadershipGauge(String role, String podName) {
        if (role == null || role.trim().isEmpty()) {
            throw new IllegalArgumentException("role must not be null or empty");
        }

        if (podName == null || podName.trim().isEmpty()) {
            throw new IllegalArgumentException("podName must not be null or empty");
        }

        // Инициализация статуса равным 0 (follower по-умолчанию)
        leadershipStatus.putIfAbsent(role, new AtomicInteger(0));

        leasePodName.put(role, podName);

        Gauge.builder(METRIC_IS_LEADER, this, metrics -> metrics.isLeader(role))
                .tag("role", role)
                .tag("pod_name", podName)
                .description("Leadership status: 1 = leader, 0 = follower")
                .register(meterRegistry);
    }

    @Override
    public void updateLeadershipStatus(String role, int status) {
        AtomicInteger currentStatus = leadershipStatus.get(role);
        if (currentStatus != null) {
            currentStatus.set(status);
        }
    }

    @Override
    public void recordLeadershipGranted(String role) {
        leadershipStartTime.put(role, new AtomicLong(System.currentTimeMillis()));
    }

    @Override
    public void recordLeadershipRevoked(String role) {
        AtomicLong startTime = leadershipStartTime.get(role);
        if (startTime != null) {
            leadershipStartTime.remove(role);
        }
    }

    @Override
    public void recordFailedAttempt(String role, String reason) {
        meterRegistry.counter(METRIC_FAILED_ATTEMPTS, "role", role, "reason", reason).increment();
    }

    @Override
    public void updateLeaseInfo(String role, String holderIdentity, long acquireTime, long renewTime,
                                int leaseDurationSeconds, int leaderTransitions) {
        leaseAcquireTime.computeIfAbsent(role, k -> new AtomicLong()).set(acquireTime);
        leaseRenewTime.computeIfAbsent(role, k -> new AtomicLong()).set(renewTime);
        this.leaseDurationSeconds.computeIfAbsent(role, k -> new AtomicInteger()).set(leaseDurationSeconds);
        this.leaseTransitions.computeIfAbsent(role, k -> new AtomicInteger()).set(leaderTransitions);

        String previousHolder = leaseHolderIdentity.get(role);
        boolean holderChanged = (previousHolder == null || !previousHolder.equals(holderIdentity));
        if (holderChanged) {
            if (previousHolder != null) {
                removeLeaseGauges(role, previousHolder);
            }

            leaseHolderIdentity.put(role, holderIdentity);
            registerLeaseGauges(role, holderIdentity);
        }
    }

    private void registerLeaseGauges(String role, String holderIdentity) {
        String podName = leasePodName.getOrDefault(role, "unknown");

        // Gauge для Acquire Time
        Gauge.builder(METRIC_PREFIX + ".lease.acquire_time",
                        () -> leaseAcquireTime.getOrDefault(role, new AtomicLong(0)).get())
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .description("Lease acquire time (epoch milliseconds)")
                .register(meterRegistry);

        // Gauge для Renew Time
        Gauge.builder(METRIC_PREFIX + ".lease.renew_time",
                        () -> leaseRenewTime.getOrDefault(role, new AtomicLong(0)).get())
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .description("Lease renew time (epoch milliseconds)")
                .register(meterRegistry);

        // Gauge для Lease Duration Seconds
        Gauge.builder(METRIC_PREFIX + ".lease.duration_seconds",
                        () -> this.leaseDurationSeconds.getOrDefault(role, new AtomicInteger(0)).get())
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .description("Lease duration in seconds")
                .register(meterRegistry);

        // Gauge для Leader Transitions
        Gauge.builder(METRIC_PREFIX + ".lease.transitions",
                        () -> this.leaseTransitions.getOrDefault(role, new AtomicInteger(0)).get())
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .description("Number of leadership transitions")
                .register(meterRegistry);
    }

    private void removeLeaseGauges(String role, String holderIdentity) {
        String podName = leasePodName.getOrDefault(role, "unknown");

        Meter acquireTimeMeter = meterRegistry.find(METRIC_PREFIX + ".lease.acquire_time")
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .meter();
        if (acquireTimeMeter != null) {
            meterRegistry.remove(acquireTimeMeter);
        }

        Meter renewTimeMeter = meterRegistry.find(METRIC_PREFIX + ".lease.renew_time")
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .meter();
        if (renewTimeMeter != null) {
            meterRegistry.remove(renewTimeMeter);
        }

        Meter durationMeter = meterRegistry.find(METRIC_PREFIX + ".lease.duration_seconds")
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .meter();
        if (durationMeter != null) {
            meterRegistry.remove(durationMeter);
        }

        Meter transitionsMeter = meterRegistry.find(METRIC_PREFIX + ".lease.transitions")
                .tag("role", role)
                .tag("pod_name", podName)
                .tag("holder_identity", holderIdentity)
                .meter();
        if (transitionsMeter != null) {
            meterRegistry.remove(transitionsMeter);
        }
    }

    private int isLeader(String role) {
        AtomicInteger status = leadershipStatus.get(role);
        return (status != null) ? status.get() : 0;
    }

}
