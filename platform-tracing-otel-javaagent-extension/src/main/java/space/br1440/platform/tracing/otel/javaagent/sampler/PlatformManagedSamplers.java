package space.br1440.platform.tracing.otel.javaagent.sampler;

import io.opentelemetry.sdk.trace.samplers.Sampler;

/**
 * Утилиты idempotency-guard'а для платформенного sampler'а (Фаза 15, PR-1).
 * <p>
 * В штатном потоке вывод named-провайдера ({@code otel.traces.sampler=platform}) передаётся в
 * {@code addSamplerCustomizer} как {@code existing} <b>на верхнем уровне</b> — SDK не вставляет
 * промежуточных обёрток между resolution named-сэмплера и применением customizer'ов. Поэтому для
 * штатного случая достаточно проверки верхнего уровня через {@link PlatformManagedSampler}.
 * <p>
 * {@link #isPlatformManaged(Sampler)} дополнительно (defense-in-depth) распознаёт платформенный
 * sampler по {@code getDescription()} — на случай, если сторонний декоратор обернул его так, что
 * маркер-интерфейс на верхнем уровне не виден.
 */
public final class PlatformManagedSamplers {

    /**
     * Сигнатура {@code getDescription()} платформенного композита (см.
     * {@link CompositeSampler#getDescription()}). Используется как defense-in-depth маркер.
     */
    static final String DESCRIPTION_MARKER = "PlatformRuleBasedSampler";

    private PlatformManagedSamplers() {
        // utility-класс
    }

    /**
     * Возвращает внутренний {@link CompositeSampler}, если {@code sampler} — платформенный
     * (через маркер-интерфейс {@link PlatformManagedSampler}); иначе {@code null}.
     */
    public static CompositeSampler findComposite(Sampler sampler) {
        if (sampler instanceof PlatformManagedSampler managed) {
            return managed.platformCompositeSampler();
        }
        return null;
    }

    /**
     * Возвращает {@code true}, если {@code sampler} уже является платформенным — по маркер-
     * интерфейсу (верхний уровень) либо по сигнатуре {@code getDescription()} (defense-in-depth).
     */
    public static boolean isPlatformManaged(Sampler sampler) {
        if (sampler == null) {
            return false;
        }
        if (sampler instanceof PlatformManagedSampler) {
            return true;
        }
        try {
            String description = sampler.getDescription();
            return description != null && description.contains(DESCRIPTION_MARKER);
        } catch (RuntimeException ignored) {
            // getDescription() не должен бросать, но делегату не доверяем — трактуем как не-платформенный
            return false;
        }
    }
}
