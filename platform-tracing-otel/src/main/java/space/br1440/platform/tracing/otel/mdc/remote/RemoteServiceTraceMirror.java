package space.br1440.platform.tracing.otel.mdc.remote;

import java.time.Duration;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.LongSupplier;

/**
 * Ограниченное trace-scoped зеркало для {@code platform.remote.service}.
 * <p>
 * Экземпляром владеет только {@link RemoteServiceMdc}. Очистка просроченных записей выполняется
 * при чтении и записи, поэтому зеркало не создаёт фоновых потоков и не требует scheduler lifecycle.
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
            throw new IllegalArgumentException("maxEntries должен быть положительным");
        }
        Objects.requireNonNull(ttl, "ttl");
        if (ttl.isZero() || ttl.isNegative()) {
            throw new IllegalArgumentException("ttl должен быть положительным");
        }

        this.maxEntries = maxEntries;
        this.ttlNanos = ttl.toNanos();
        this.ticker = Objects.requireNonNull(ticker, "ticker");
    }

    synchronized void put(String traceId, String remoteService) {
        if (closed || isBlank(traceId) || isBlank(remoteService)) {
            return;
        }

        long now = ticker.getAsLong();
        removeExpired(now);
        entries.remove(traceId);
        entries.put(traceId, new Entry(remoteService, now));
        evictOverflow();
    }

    synchronized Optional<String> get(String traceId) {
        if (closed || isBlank(traceId)) {
            return Optional.empty();
        }

        removeExpired(ticker.getAsLong());
        Entry entry = entries.get(traceId);
        if (entry == null || isBlank(entry.remoteService())) {
            return Optional.empty();
        }
        return Optional.of(entry.remoteService());
    }

    synchronized void clear(String traceId) {
        if (!isBlank(traceId)) {
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private record Entry(String remoteService, long createdAtNanos) {
    }
}
