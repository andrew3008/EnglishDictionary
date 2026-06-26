package space.br1440.platform.errorhandling.autoconfigure;

import io.micrometer.context.ContextRegistry;
import io.micrometer.context.ThreadLocalAccessor;
import org.slf4j.MDC;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;

import java.util.Map;

/**
 * Двунаправленный мост между Reactor {@link reactor.util.context.Context} и
 * SLF4J {@link MDC} через Micrometer Context Propagation.
 *
 * <h2>Зачем это нужно</h2>
 * <p>{@link reactor.core.publisher.Hooks#enableAutomaticContextPropagation()}
 * восстанавливает ThreadLocal только для тех ключей, для которых явно
 * зарегистрирован {@link ThreadLocalAccessor} в {@link ContextRegistry}.
 * Без этого аксессора MDC, помещённый в Reactor Context через
 * {@code .contextWrite(...)}, не появится в логах при переключении потоков.
 *
 * <h2>Модель ключа</h2>
 * <p>Аксессор хранит весь снимок MDC под единым ключом {@link #KEY},
 * а не по одному ключу на каждое имя MDC. Преимущества:
 * <ul>
 *   <li>Один атомарный set/restore — нет частичных перезаписей при гонках;</li>
 *   <li>Сохраняются все upstream-ключи (traceId, spanId), даже те, о которых мы не знаем заранее;</li>
 *   <li>Не нужно перерегистрировать accessor при каждом новом MDC-ключе.</li>
 * </ul>
 *
 * <h2>restore()</h2>
 * <p>{@link #restore(Map)} полностью заменяет MDC снимком из контекста.
 * При закрытии {@link io.micrometer.context.ContextSnapshot.Scope} вызывается
 * {@link #restore()} (без аргументов), который очищает MDC. Такое поведение
 * предотвращает утечку MDC между запросами в shared-thread окружениях (Netty event loop, scheduler-pool).
 */
public final class MdcThreadLocalAccessor implements ThreadLocalAccessor<Map<String, String>> {

    public static final String KEY = "mdc.context.snapshot";

    @Override
    @NonNull
    public Object key() {
        return KEY;
    }

    @Override
    @Nullable
    public Map<String, String> getValue() {
        return MDC.getCopyOfContextMap();
    }

    @Override
    public void setValue(@NonNull Map<String, String> value) {
        MDC.setContextMap(value);
    }

    @Override
    public void setValue() {
        MDC.clear();
    }

    @Override
    public void restore(@NonNull Map<String, String> previousValue) {
        MDC.setContextMap(previousValue);
    }

    @Override
    public void restore() {
        MDC.clear();
    }
}
