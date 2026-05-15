package space.br1440.platform.metrics.buildinfo;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tags;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;

import java.io.InputStream;

@RequiredArgsConstructor
public class BuildInfoMetric {

    private final MeterRegistry meterRegistry;

    @Getter
    private BuildInfo buildInfo;

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
                "platform.info",
                // Версия артефакта из сгенерированного build-info.json
                // (это тег, а не «значение по умолчанию» для gauge — см. Javadoc класса).
                Tags.of("version", buildInfo.version()),
                // Не связано с version/buildTime: фиксированное число «метка присутствия» для Prometheus/Micrometer.
                // Всегда неизменяемая константа для снижения кардинальности тэга в TSDB
                1.0
        );
    }
}
