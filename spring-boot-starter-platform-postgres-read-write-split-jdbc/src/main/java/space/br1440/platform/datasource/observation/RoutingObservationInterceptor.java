package space.br1440.platform.datasource.observation;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
import space.br1440.platform.datasource.routing.DataSourceType;

import java.util.function.Function;

/**
 * Создаёт Micrometer {@link Observation} при маршрутизации соединения.
 * <p>
 * Когда в classpath есть {@code micrometer-observation} и в контексте зарегистрирован
 * {@link ObservationRegistry}, этот интерцептор оборачивает получение соединения
 * в observation span. Если Micrometer Tracing подключён — span автоматически
 * экспортируется в трейсинг-бэкенд (Jaeger, Tempo, OTel Collector).
 * <p>
 *
 * <h3>Пример использования</h3>
 * <pre>{@code
 * RoutingObservationInterceptor interceptor = ...;
 * Observation obs = interceptor.createObservation(intent, target);
 * obs.observe(() -> target.getConnection());
 * }</pre>
 */
public class RoutingObservationInterceptor {

    private final ObservationRegistry registry;
    private final RoutingObservationConvention convention;
    private final Function<Object, String> poolNameExtractor;

    public RoutingObservationInterceptor(ObservationRegistry registry,
                                         RoutingObservationConvention convention,
                                         Function<Object, String> poolNameExtractor) {
        this.registry = registry;
        this.convention = convention;
        this.poolNameExtractor = poolNameExtractor;
    }

    public Observation createObservation(DataSourceType intent, Object target) {
        String poolName = extractPoolName(target);
        RoutingObservationContext context = new RoutingObservationContext(intent, poolName);
        return Observation.createNotStarted(convention, () -> context, registry);
    }

    private String extractPoolName(Object target) {
        if (target == null || poolNameExtractor == null) {
            return null;
        }

        try {
            return poolNameExtractor.apply(target);
        } catch (RuntimeException ex) {
            // Имя пула — best-effort метаданные; падение callback'а не должно ломать observation.
            return null;
        }
    }
}
