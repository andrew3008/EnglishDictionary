package space.br1440.platform.tracing.autoconfigure.kafka;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.TraceControlHeaderInjector;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Регистрирует {@link PlatformKafkaProducerInterceptor} во всех {@link DefaultKafkaProducerFactory}
 * (non-destructive) и прокидывает зависимости (policy/injector) через producer-config map.
 * <p>
 * Платформенный интерсептор добавляется <b>последним</b> в {@code interceptor.classes}, чтобы
 * инжектировать платформенные заголовки после бизнес-трансформаций пользовательских интерсепторов,
 * но до фактической отправки. Существующие интерсепторы сохраняются.
 */
public final class PlatformKafkaProducerFactoryCustomizer implements DefaultKafkaProducerFactoryCustomizer {

    private final OutboundPropagationPolicy policy;
    private final TraceControlHeaderInjector injector;

    public PlatformKafkaProducerFactoryCustomizer(OutboundPropagationPolicy policy, TraceControlHeaderInjector injector) {
        this.policy = policy;
        this.injector = injector;
    }

    @Override
    public void customize(DefaultKafkaProducerFactory<?, ?> producerFactory) {
        Map<String, Object> current = producerFactory.getConfigurationProperties();

        List<String> interceptors = new ArrayList<>(existingInterceptors(current));
        String platformInterceptor = PlatformKafkaProducerInterceptor.class.getName();
        if (!interceptors.contains(platformInterceptor)) {
            interceptors.add(platformInterceptor); // последним
        }

        Map<String, Object> updates = new HashMap<>();
        updates.put(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG, interceptors);
        updates.put(PlatformKafkaProducerInterceptor.CONFIG_POLICY, policy);
        updates.put(PlatformKafkaProducerInterceptor.CONFIG_INJECTOR, injector);
        producerFactory.updateConfigs(updates);
    }

    @SuppressWarnings("unchecked")
    private static List<String> existingInterceptors(Map<String, Object> config) {
        Object value = config.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
        if (value == null) {
            return List.of();
        }
        if (value instanceof List) {
            // Может быть List<String> либо List<Class<?>> — нормализуем к именам классов.
            List<String> result = new ArrayList<>();
            for (Object item : (List<Object>) value) {
                result.add(item instanceof Class ? ((Class<?>) item).getName() : String.valueOf(item));
            }
            return result;
        }
        if (value instanceof String s && !s.isBlank()) {
            return Arrays.stream(s.split(",")).map(String::trim).filter(p -> !p.isEmpty()).toList();
        }
        return List.of();
    }
}
