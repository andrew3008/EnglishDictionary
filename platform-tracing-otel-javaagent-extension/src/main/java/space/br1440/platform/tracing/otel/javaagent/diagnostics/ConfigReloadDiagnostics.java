package space.br1440.platform.tracing.otel.javaagent.diagnostics;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

public final class ConfigReloadDiagnostics {

    static final int AUDIT_CAPACITY = 32;

    private static final ConfigReloadDiagnostics SHARED = new ConfigReloadDiagnostics();

    private final LongAdder updatesApplied = new LongAdder();
    private final LongAdder updatesRejected = new LongAdder();
    private final AtomicLong lastUpdateEpochMs = new AtomicLong(0L);
    private final AtomicReference<String> lastSource = new AtomicReference<>("none");
    private final AtomicReference<String> lastDomain = new AtomicReference<>("none");

    private final Object auditLock = new Object();
    private final Deque<AuditEntry> audit = new ArrayDeque<>(AUDIT_CAPACITY);

    public static ConfigReloadDiagnostics shared() {
        return SHARED;
    }

    public void record(String domain, boolean applied, String source, long version) {
        String safeDomain = domain == null ? "unknown" : domain;
        String safeSource = source == null ? "unknown" : source;
        long now = System.currentTimeMillis();

        if (applied) {
            updatesApplied.increment();
        } else {
            updatesRejected.increment();
        }
        lastUpdateEpochMs.set(now);
        lastSource.set(safeSource);
        lastDomain.set(safeDomain);

        AuditEntry entry = new AuditEntry(now, safeDomain, applied, safeSource, version);
        synchronized (auditLock) {
            if (audit.size() >= AUDIT_CAPACITY) {
                audit.removeFirst();
            }

            audit.addLast(entry);
        }
    }

    public long getUpdatesApplied() {
        return updatesApplied.sum();
    }

    public long getUpdatesRejected() {
        return updatesRejected.sum();
    }

    public long getLastUpdateEpochMs() {
        return lastUpdateEpochMs.get();
    }

    public String getLastSource() {
        return lastSource.get();
    }

    public String getLastDomain() {
        return lastDomain.get();
    }

    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("updates.applied", getUpdatesApplied());
        snapshot.put("updates.rejected", getUpdatesRejected());
        snapshot.put("last_update.epoch_ms", getLastUpdateEpochMs());
        return snapshot;
    }

    public List<AuditEntry> auditTrail() {
        synchronized (auditLock) {
            return List.copyOf(audit);
        }
    }

    public record AuditEntry(long timestampEpochMs,
                             String domain,
                             boolean applied,
                             String source,
                             long version) {

        @Override
        public String toString() {
            return "%d %s %s source=%s v=%d".formatted(
                    timestampEpochMs,
                    domain,
                    applied ? "applied" : "rejected",
                    source,
                    version);
        }
    }
}
