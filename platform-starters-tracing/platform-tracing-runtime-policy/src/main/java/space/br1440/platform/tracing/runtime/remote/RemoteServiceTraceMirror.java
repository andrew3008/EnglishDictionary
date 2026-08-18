package space.br1440.platform.tracing.runtime.remote;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

import space.br1440.platform.common.util.StringUtils;

/**
 * Ограниченное trace-scoped зеркало для {@code platform.remote.service}.
 */
final class RemoteServiceTraceMirror implements AutoCloseable {

    static final int DEFAULT_MAX_ENTRIES = 4_096;
    static final Duration DEFAULT_TTL = Duration.ofMinutes(5);

    private final int maxEntries;
    private final long ttlNanos;
    private final LongSupplier ticker;
    private final LinkedHashMap<String, Entry> entries = new LinkedHashMap<>();
    private boolean closed;

    RemoteServiceTraceMirror() {
        this(DEFAULT_MAX_ENTRIES, DEFAULT_TTL, System::nanoTime);
    }

    RemoteServiceTraceMirror(int maxEntries, Duration ttl, LongSupplier ticker) {
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be positive");
        }

        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl must be positive");
        }

        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    synchronized void put(String traceId, String remoteService) {
        if (closed || StringUtils.isNullOrBlank(traceId) || StringUtils.isNullOrBlank(remoteService)) {
            return;
        }

        long now = ticker.getAsLong();
        removeExpired(now);
        entries.remove(traceId);
        entries.put(traceId, new Entry(remoteService, now));
        evictOverflow();
    }

    synchronized Optional<String> get(String traceId) {
        if (closed || StringUtils.isNullOrBlank(traceId)) {
            return Optional.empty();
        }

        removeExpired(ticker.getAsLong());

        Entry entry = entries.get(traceId);
        if (entry == null || StringUtils.isNullOrBlank(entry.remoteService())) {
            return Optional.empty();
        }

        return Optional.of(entry.remoteService());
    }

    synchronized void clear(String traceId) {
        if (!StringUtils.isNullOrBlank(traceId)) {
            entries.remove(traceId);
        }
    }

    synchronized int size() {
        removeExpired(ticker.getAsLong());
        return entries.size();
    }

    @Override
    public synchronized void close() {
        entries.clear();
        closed = true;
    }

    private void removeExpired(long now) {
        Iterator<Map.Entry<String, Entry>> iterator = entries.entrySet().iterator();
        while (iterator.hasNext()) {
            Entry entry = iterator.next().getValue();
            if (now - entry.createdAtNanos() < ttlNanos) {
                break;
            }

            iterator.remove();
        }
    }

    private void evictOverflow() {
        Iterator<String> iterator = entries.keySet().iterator();
        while (entries.size() > maxEntries && iterator.hasNext()) {
            iterator.next();
            iterator.remove();
        }
    }

    private record Entry(String remoteService, long createdAtNanos) {
    }
}
