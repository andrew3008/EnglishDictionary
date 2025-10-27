package space.br1440.platform.leaderelection.leaderelection;

import lombok.RequiredArgsConstructor;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import space.br1440.platform.leaderelection.configuration.LeaderElectionProperties;
import space.br1440.platform.leaderelection.event.LeadershipEventPublisher;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.DistributedLockRegistry;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;
import space.br1440.platform.leaderelection.metrics.LeaderElectionMetricsInterface;
import space.br1440.platform.leaderelection.util.LeadershipNameResolver;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * Реализует двухфазный алгоритм из Kubernetes client-go LeaderElector:
 * 1. Fast Path - optimistic update для текущего лидера
 * 2. Slow Path - full validation с fetch текущего состояния
 *
 * @see <a href="https://github.com/kubernetes/client-go/blob/master/tools/leaderelection/leaderelection.go">Kubernetes LeaderElector</a>
 */
@RequiredArgsConstructor
public class LeaderElector {

    private final LeaderElectionProperties properties;
    private final String holderIdentity;
    private final DistributedLock distributedLock;
    private final LeadershipEventPublisher eventPublisher;
    private final LeaderElectionMetricsInterface metrics;

    private volatile LeaseRecord observedRecord = new LeaseRecord();
    private volatile Instant observedTime = Instant.MIN;
    private final Object observedRecordLock = new Object();
    private final Clock clock = Clock.systemUTC();

    private volatile String reportedLeader = "";
    private volatile boolean hadLeadership = false;

    public LeaderElector(LeaderElectionProperties properties,
                         Environment environment,
                         DistributedLockRegistry lockRegistry,
                         LeadershipEventPublisher eventPublisher,
                         LeaderElectionMetricsInterface metrics) {
        this.properties = properties;
        this.holderIdentity = resolveHolderIdentity(environment);

        String leadershipName = LeadershipNameResolver.resolve(properties, environment);
        this.distributedLock = lockRegistry.obtain(leadershipName);
        this.eventPublisher = eventPublisher;

        this.metrics = metrics;
        metrics.registerLeadershipGauge(leadershipName, this.holderIdentity);
    }

    public void runLeaderElection(BooleanSupplier shouldContinue) {
        try {
            while (shouldContinue.getAsBoolean()) {
                try {
                    boolean acquired = tryAcquireOrRenew();
                    
                    notifyTransitionIfChanged();
                    
                    String leadershipName = properties.getLeadership().getLeadershipName();
                    metrics.updateLeadershipStatus(leadershipName, acquired ? 1 : 0);
                    
                    Duration sleepDuration = acquired
                            ? properties.getLeadership().getRenewDeadline()
                            : properties.getLeadership().getRetryPeriod();

                    Thread.sleep(sleepDuration.toMillis());
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                } catch (Exception e) {
                    // При ошибке статус = 0 (follower)
                    String leadershipName = properties.getLeadership().getLeadershipName();
                    metrics.updateLeadershipStatus(leadershipName, 0);
                    
                    metrics.recordFailedAttempt(
                        leadershipName,
                        "main_loop_exception: " + e.getClass().getSimpleName()
                    );
                    
                    notifyTransitionIfChanged();
                    
                    try {
                        Thread.sleep(properties.getLeadership().getRetryPeriod().toMillis());
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } finally {
            stop();
        }
    }

    public void stop() {
        if (hadLeadership) {
            hadLeadership = false;
            String leadershipName = properties.getLeadership().getLeadershipName();
            metrics.recordLeadershipRevoked(leadershipName);
            eventPublisher.publishOnRevoked();
        }
    }

    public boolean isLeader() {
        try {
            LeaseRecord currentRecord = distributedLock.getCurrentRecord();
            if (currentRecord == null) {
                return false; // Нет записи в Redis - не лидер
            }
            return holderIdentity.equals(currentRecord.getHolderIdentity());
        } catch (Exception e) {
            // При ошибке проверяем observedRecord как fallback
            return holderIdentity.equals(getHolderIdentity());
        }
    }

    private boolean tryAcquireOrRenew() {
        Instant now = clock.instant();

        // ========== FAST PATH - Optimistic Update ==========
        // Если уже лидер и lease валиден, пытаемся optimistic update
        // без предварительного fetch текущего состояния
        if (isLeader() && isLeaseValid(now)) {
            LeaseRecord oldObservedRecord = getObservedRecord();
            LeaseRecord optimisticRecord = new LeaseRecord(
                    holderIdentity,
                    oldObservedRecord.getAcquireTime(),
                    now, // renewTime
                    (int) properties.getLeadership().getLeaseDuration().getSeconds(),
                    oldObservedRecord.getLeaderTransitions(), // Renewal не меняет transitions
                    oldObservedRecord.getVersion() // Используем существующую версию
            );

            try {
                if (distributedLock.updateRecord(optimisticRecord)) {
                    setObservedRecord(optimisticRecord);
                    return true;
                }
            } catch (Exception e) {
                String leadershipName = properties.getLeadership().getLeadershipName();
                metrics.recordFailedAttempt(leadershipName, "fast_path_exception");
            }
        }

        // ========== SLOW PATH - Full Validation ==========
        // Получаем текущее состояние lease из distributed storage
        LeaseRecord oldLeaderElectionRecord;
        try {
            oldLeaderElectionRecord = distributedLock.getCurrentRecord();
        } catch (Exception e) {
            String leadershipName = properties.getLeadership().getLeadershipName();
            metrics.recordFailedAttempt(leadershipName, "fetch_record_failed");
            return false;
        }

        // Если lease не существует - создаём новый
        if (oldLeaderElectionRecord == null) {
            LeaseRecord newRecord = createLeaderElectionRecord();
            try {
                boolean created = distributedLock.createRecord(newRecord);
                if (created) {
                    setObservedRecord(newRecord);
                    return true;
                }
            } catch (Exception createError) {
                String leadershipName = properties.getLeadership().getLeadershipName();
                metrics.recordFailedAttempt(leadershipName, "create_lease_exception");
                return false;
            }
            return false;
        }

        // Обновляем observed state если он изменился
        if (getObservedRecord() == null || getObservedRecord().compareTo(oldLeaderElectionRecord) != 0) {
            setObservedRecord(oldLeaderElectionRecord);
        }

        // Проверяем: владеет ли другой holder валидным lease
        boolean isCurrentLeader = holderIdentity.equals(oldLeaderElectionRecord.getHolderIdentity());
        if (oldLeaderElectionRecord.getHolderIdentity() != null &&
                !oldLeaderElectionRecord.getHolderIdentity().isEmpty() &&
                isLeaseValidForRecord(oldLeaderElectionRecord, now) &&
                !isCurrentLeader) {
            return false;
        }

        // Формируем финальную запись: renewal для текущего лидера или новое acquisition
        LeaseRecord finalRecord;
        if (isCurrentLeader) {
            finalRecord = new LeaseRecord(
                    holderIdentity,
                    oldLeaderElectionRecord.getAcquireTime(),
                    now, // renewTime
                    (int) properties.getLeadership().getLeaseDuration().getSeconds(),
                    oldLeaderElectionRecord.getLeaderTransitions(), // Renewal не меняет transitions
                    oldLeaderElectionRecord.getVersion() // Используем существующую версию
            );
        } else {
            String oldHolder = oldLeaderElectionRecord.getHolderIdentity();
            boolean isLeaderChange = oldHolder != null &&
                                    !oldHolder.isEmpty() &&
                                    !oldHolder.equals(holderIdentity);

            int newLeaderTransitions = isLeaderChange
                ? oldLeaderElectionRecord.getLeaderTransitions() + 1 
                : oldLeaderElectionRecord.getLeaderTransitions(); // Оставляем старое значение (0 для первого лидера)
            
            finalRecord = new LeaseRecord(
                    holderIdentity,
                    now, // acquireTime
                    now, // renewTime
                    (int) properties.getLeadership().getLeaseDuration().getSeconds(),
                    newLeaderTransitions,
                    (oldLeaderElectionRecord.getVersion() + 1)
            );
        }

        // Пытаемся обновить lease с новой записью
        try {
            if (distributedLock.updateRecord(finalRecord)) {
                setObservedRecord(finalRecord);
                return true;
            } else {
                // updateRecord() == false это нормально (конфликт версий / оптимистическая блокировка)
                // Это происходит во время обычной конкуренции за лидерство
                return false;
            }
        } catch (Exception e) {
            String leadershipName = properties.getLeadership().getLeadershipName();
            metrics.recordFailedAttempt(leadershipName, "update_lease_exception");
            return false;
        }
    }

    private LeaseRecord createLeaderElectionRecord() {
        Instant now = clock.instant();
        return new LeaseRecord(
                holderIdentity,
                now,
                now,
                (int) properties.getLeadership().getLeaseDuration().getSeconds(),
                0, // Первый лидер = 0 transitions
                1  // Новая запись = version 1 для оптимистической блокировки
        );
    }

    private LeaseRecord getObservedRecord() {
        synchronized (observedRecordLock) {
            return observedRecord;
        }
    }

    private String getHolderIdentity() {
        LeaseRecord record = getObservedRecord();
        return (record != null) ? record.getHolderIdentity() : null;
    }

    private void setObservedRecord(LeaseRecord record) {
        synchronized (observedRecordLock) {
            this.observedRecord = record;
            this.observedTime = clock.instant();
        }
        
        if (record != null && record.getHolderIdentity() != null) {
            metrics.updateLeaseInfo(
                    properties.getLeadership().getLeadershipName(),
                    record.getHolderIdentity(),
                    record.getAcquireTime().toEpochMilli(),
                    record.getRenewTime().toEpochMilli(),
                    record.getLeaseDurationSeconds(),
                    record.getLeaderTransitions()
            );
        }
    }

    private boolean isLeaseValid(Instant now) {
        synchronized (observedRecordLock) {
            if (observedRecord.getHolderIdentity() == null || observedRecord.getHolderIdentity().isEmpty()) {
                return false;
            }
            
            Instant leaseExpiry = observedTime.plusSeconds(observedRecord.getLeaseDurationSeconds());
            return leaseExpiry.isAfter(now);
        }
    }

    private boolean isLeaseValidForRecord(LeaseRecord record, Instant now) {
        if (record.getHolderIdentity() == null || record.getHolderIdentity().isEmpty()) {
            return false;
        }
        
        Instant leaseExpiry = record.getRenewTime().plusSeconds(record.getLeaseDurationSeconds());
        return leaseExpiry.isAfter(now);
    }

    private void notifyTransitionIfChanged() {
        String currentLeader = getHolderIdentity();
        if (currentLeader != null && !currentLeader.equals(reportedLeader)) {
            reportedLeader = currentLeader;
        }

        boolean isLeaderNow = holderIdentity.equals(currentLeader);
        String leadershipName = properties.getLeadership().getLeadershipName();
        if (!hadLeadership && isLeaderNow) {
            hadLeadership = true;
            metrics.recordLeadershipGranted(leadershipName);
            eventPublisher.publishOnGranted();
        } else if (hadLeadership && !isLeaderNow) {
            hadLeadership = false;
            metrics.recordLeadershipRevoked(leadershipName);
            eventPublisher.publishOnRevoked();
        }
    }

    private String resolveHolderIdentity(Environment environment) {
        String pod = environment.getProperty("POD_NAME");
        if (StringUtils.hasText(pod)) {
            return pod.trim();
        }

        String hostname = environment.getProperty("HOSTNAME");
        if (StringUtils.hasText(hostname)) {
            return hostname.trim();
        }

        try {
            String localHostname = InetAddress.getLocalHost().getHostName();
            if (localHostname != null && !localHostname.trim().isEmpty()) {
                return localHostname.trim();
            }
        } catch (UnknownHostException ignored) {
        }

        return UUID.randomUUID().toString();
    }

}