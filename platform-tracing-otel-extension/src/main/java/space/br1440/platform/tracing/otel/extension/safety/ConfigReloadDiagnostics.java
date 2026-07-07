package space.br1440.platform.tracing.otel.extension.safety;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;

/**
 * Наблюдаемость перезагрузки динамической конфигурации платформы (Фаза 14).
 * <p>
 * Все runtime-апдейты политики (sampling/scrubbing/validation/export/propagation/log-level)
 * проходят через доменные JMX MBean-ы (PlatformSamplingControl и др.) и регистрируются здесь: счётчики
 * applied/rejected, источник и время последнего изменения, а также короткий audit-trail
 * (кольцевой буфер последних изменений) для разбора инцидентов «кто и что переключил».
 * <p>
 * Счётчики живут в classloader'е агента (Вариант А, ADR-processor-errors-metric): прямых вызовов
 * Micrometer нет, экспозиция в Prometheus — через JMX + polling-MeterBinder на стороне стартера.
 * Namespace метрик — {@code platform.tracing.config.*}.
 *
 * <p><b>Потокобезопасность:</b> счётчики атомарны; audit-trail защищён собственным локом
 * (запись редкая — только при изменении конфигурации, не на hot-path).
 */
public final class ConfigReloadDiagnostics {

    /** Максимум хранимых записей audit-trail (bounded — без утечки памяти). */
    static final int AUDIT_CAPACITY = 32;

    private static final ConfigReloadDiagnostics SHARED = new ConfigReloadDiagnostics();

    private final LongAdder updatesApplied = new LongAdder();
    private final LongAdder updatesRejected = new LongAdder();
    private final AtomicLong lastUpdateEpochMs = new AtomicLong(0L);
    private final AtomicReference<String> lastSource = new AtomicReference<>("none");
    private final AtomicReference<String> lastDomain = new AtomicReference<>("none");

    private final Object auditLock = new Object();
    private final Deque<String> audit = new ArrayDeque<>(AUDIT_CAPACITY);

    public static ConfigReloadDiagnostics shared() {
        return SHARED;
    }

    /**
     * Регистрирует попытку обновления конфигурации одного домена.
     *
     * @param domain  логический домен ({@code sampling}/{@code scrubbing}/...) — low-cardinality
     * @param applied {@code true} — изменение применено; {@code false} — отклонено (LKG сохранён)
     * @param source  источник изменения ({@code JMX}/{@code startup}/...)
     * @param version версия снимка после изменения (или текущая при отклонении)
     */
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

        String entry = now + " " + safeDomain + " " + (applied ? "applied" : "rejected")
                + " source=" + safeSource + " v=" + version;
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

    /** Снимок счётчиков для JMX/actuator. Ключи стабильны и low-cardinality. */
    public Map<String, Long> snapshot() {
        Map<String, Long> snapshot = new LinkedHashMap<>();
        snapshot.put("updates.applied", getUpdatesApplied());
        snapshot.put("updates.rejected", getUpdatesRejected());
        snapshot.put("last_update.epoch_ms", getLastUpdateEpochMs());
        return snapshot;
    }

    /** Копия audit-trail (от старых к новым записям). */
    public String[] auditTrail() {
        synchronized (auditLock) {
            return audit.toArray(new String[0]);
        }
    }
}
