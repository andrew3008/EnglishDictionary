package space.br1440.platform.tracing.autoconfigure.integrations.kafka;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import lombok.RequiredArgsConstructor;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.springframework.boot.autoconfigure.kafka.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;

import space.br1440.platform.common.util.StringUtils;
import space.br1440.platform.tracing.api.propagation.control.OutboundPropagationPolicy;
import space.br1440.platform.tracing.api.propagation.control.PlatformOutboundPropagation;
import space.br1440.platform.tracing.autoconfigure.identity.RequestIdentityBoundarySupport;

@RequiredArgsConstructor
public final class PlatformKafkaProducerFactoryCustomizer implements DefaultKafkaProducerFactoryCustomizer {

    private final OutboundPropagationPolicy policy;
    private final PlatformOutboundPropagation propagation;
    private final RequestIdentityBoundarySupport identityBoundary;

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
        updates.put(PlatformKafkaProducerInterceptor.CONFIG_PROPAGATION, propagation);
        updates.put(PlatformKafkaProducerInterceptor.CONFIG_IDENTITY, identityBoundary);
        producerFactory.updateConfigs(updates);
    }

    private static List<String> existingInterceptors(Map<String, Object> config) {
        Object value = config.get(ProducerConfig.INTERCEPTOR_CLASSES_CONFIG);
        switch (value) {
            case null -> {
                return List.of();
            }

            case List<?> list -> {
                List<String> result = new ArrayList<>();
                for (Object item : list) {
                    String itemName = (item instanceof Class<?> cls) ? cls.getName() : String.valueOf(item);
                    result.add(itemName);
                }

                return result;
            }

            case String s when StringUtils.hasText(s) -> {
                return Arrays.stream(s.split(","))
                        .map(String::trim)
                        .filter(StringUtils::hasText)
                        .toList();
            }

            default -> {
            }
        }

        return List.of();
    }
}
