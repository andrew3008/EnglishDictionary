package space.br1440.platform.leaderelection.execution.keepalive;

import org.jetbrains.annotations.NotNull;
import space.br1440.platform.leaderelection.lock.DistributedLock;
import space.br1440.platform.leaderelection.lock.persistence.model.LeaseRecord;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;

public class KeepAliveLock implements DistributedLock {

    private final DistributedLock delegate;
    private final String lockKey;
    private final Duration lockAtMostFor;
    private final Duration extensionPeriod;
    private final Instant keepAliveLockUntil;
    private final ScheduledExecutorService scheduler;

    private final Object stateLock = new Object();
    private ScheduledFuture<?> extensionTask;
    private volatile boolean active = false;
    private volatile Instant currentLockExpiresAt;

    public KeepAliveLock(DistributedLock delegate,
                         String lockKey,
                         Duration lockAtMostFor,
                         Duration keepAliveLockUntil,
                         ScheduledExecutorService scheduler) {
        this.delegate = delegate;
        this.lockKey = lockKey;
        this.lockAtMostFor = lockAtMostFor;
        this.extensionPeriod = lockAtMostFor.dividedBy(2);
        this.keepAliveLockUntil = Instant.now().plus(keepAliveLockUntil);
        this.scheduler = scheduler;
        this.currentLockExpiresAt = Instant.now().plus(lockAtMostFor);
    }

    public void startExtension() {
        synchronized (stateLock) {
            if (active) {
                throw new IllegalStateException("Extension already started for lock: " + lockKey);
            }

            active = true;
            long extensionPeriodMs = extensionPeriod.toMillis();
            extensionTask = scheduler.scheduleAtFixedRate(
                    this::extendLock,
                    extensionPeriodMs,
                    extensionPeriodMs,
                    TimeUnit.MILLISECONDS
            );
        }
    }

    public void stopExtension() {
        synchronized (stateLock) {
            if (!active) {
                return;
            }

            active = false;

            if (extensionTask != null && !extensionTask.isCancelled()) {
                extensionTask.cancel(false);
            }
        }
    }

    @Override
    public void lock() {
        delegate.lock();
    }

    @Override
    public void lock(Duration ttl) throws InterruptedException {
        delegate.lock(ttl);
    }

    @Override
    public void lockInterruptibly() throws InterruptedException {
        delegate.lockInterruptibly();
    }

    @Override
    public boolean tryLock() {
        return delegate.tryLock();
    }

    @Override
    public boolean tryLock(long time, @NotNull TimeUnit unit) throws InterruptedException {
        return delegate.tryLock(time, unit);
    }

    @Override
    public void unlock() {
        stopExtension();
        delegate.unlock();
    }

    @Override
    public @NotNull Condition newCondition() {
        throw new UnsupportedOperationException("Conditions are not supported for KeepAliveDistributedLock");
    }

    @Override
    public LeaseRecord getCurrentRecord() {
        return delegate.getCurrentRecord();
    }

    @Override
    public boolean createRecord(LeaseRecord record) {
        return delegate.createRecord(record);
    }

    @Override
    public boolean updateRecord(LeaseRecord record) {
        return delegate.updateRecord(record);
    }

    private void extendLock() {
        synchronized (stateLock) {
            if (!active) {
                return;
            }

            Instant now = Instant.now();

            if (currentLockExpiresAt.isBefore(now)) {
                stopExtension();
                return;
            }

            if (now.isAfter(keepAliveLockUntil)) {
                stopExtension();
                return;
            }

            try {
                LeaseRecord currentRecord = delegate.getCurrentRecord();
                if (currentRecord == null) {
                    stopExtension();
                    return;
                }

                LeaseRecord extendedRecord = new LeaseRecord(
                        currentRecord.getHolderIdentity(),
                        currentRecord.getAcquireTime(),
                        now,
                        (int) lockAtMostFor.getSeconds(),
                        currentRecord.getLeaderTransitions(),
                        currentRecord.getVersion()
                );

                boolean extended = delegate.updateRecord(extendedRecord);
                if (extended) {
                    currentLockExpiresAt = now.plus(lockAtMostFor);
                } else {
                    stopExtension();
                }
            } catch (Exception e) {
                stopExtension();
            }
        }
    }

}

