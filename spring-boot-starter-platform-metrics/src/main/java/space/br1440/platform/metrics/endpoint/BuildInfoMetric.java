package space.br1440.platform.metrics.endpoint;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;

public class BuildInfoMetric {

    private final MeterRegistry meterRegistry;
    private BuildInfo buildInfo;

    public BuildInfoMetric(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    @PostConstruct
    void init() {

        Resource resource = new ClassPathResource("build-info.json");

        if (!resource.exists() || !resource.isReadable()) {
            throw new RuntimeException("Resource 'build-info.json' not found or not readable.");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            ObjectMapper objectMapper = new ObjectMapper();
            buildInfo = objectMapper.readValue(inputStream, BuildInfo.class);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        meterRegistry.gauge(
                "platform.info",  // имя метрики
                // теги для дополнительной информации
                Tags.of("version", buildInfo.version()),
                1.0  // значение метрики (можно любое, важно наличие тегов)
        );

    }

    public BuildInfo getBuildInfo() {
        return buildInfo;
    }
}
