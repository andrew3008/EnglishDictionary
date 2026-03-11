package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import space.br1440.platform.healthchecks.configuration.MinioHealthAutoConfiguration;
import space.br1440.platform.healthchecks.minio.support.MinioContainerSupport;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        classes = {
                MinioHealthAutoConfigurationIntegrationTest.InfraConfig.class,
                MinioHealthAutoConfiguration.class
        }
)
@DisplayName("MinioHealthAutoConfiguration: Spring Boot auto-configuration")
class MinioHealthAutoConfigurationIntegrationTest {

    @DynamicPropertySource
    static void registerMinioProperties(DynamicPropertyRegistry registry) {
        registry.add("platform.health.minio.async.enabled", () -> "false");
    }

    @SpringBootApplication
    static class InfraConfig {
        @Bean
        MinioClient minioClient() {
            return MinioContainerSupport.CLIENT;
        }
    }

    @Autowired
    private MinioHealthProperties properties;

    @Autowired
    @Qualifier("minioHealthIndicator")
    private HealthIndicator minioHealthIndicator;

    @Autowired
    private MinioBucketHealthCheck minioBucketHealthCheck;

    @Autowired
    private MinioSentinelHealthCheck minioSentinelHealthCheck;

    @Test
    @DisplayName("Контейнер запущен, бины MinIO health check присутствуют")
    void contextLoadsAndBeansArePresent() {
        assertThat(MinioContainerSupport.MINIO.isRunning()).isTrue();
        assertThat(properties).isNotNull();
        assertThat(minioHealthIndicator).isNotNull();
    }

    @Test
    @DisplayName("Async отключён в тесте — свойство отражается в properties")
    void asyncDisabledInTest() {
        assertThat(properties.getAsync().isEnabled()).isFalse();
    }

    @Test
    @DisplayName("Статус UP при доступном MinIO")
    void statusUpWhenMinioAvailable() {
        assertThat(minioHealthIndicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Details содержат обязательные поля корректных типов")
    void detailsContainMandatoryFieldsWithCorrectTypes() {
        // When
        var details = minioHealthIndicator.health().getDetails();

        // Then
        assertThat(details.get("service")).isInstanceOf(String.class).isEqualTo("MinIO");
        assertThat(details.get("checkType")).isInstanceOf(String.class);
        assertThat(details.get("responseTime")).isInstanceOf(String.class);
    }

    @Test
    @DisplayName("в контексте зарегистрирован бин MinioBucketHealthCheck")
    void shouldRegisterMinioBucketHealthCheckBean() {
        assertThat(minioBucketHealthCheck).isNotNull();
    }

    @Test
    @DisplayName("в контексте зарегистрирован бин MinioSentinelHealthCheck")
    void shouldRegisterMinioSentinelHealthCheckBean() {
        assertThat(minioSentinelHealthCheck).isNotNull();
    }

    @Test
    @DisplayName("minioHealthIndicator является экземпляром MinioHealthIndicator")
    void shouldExposeHealthIndicatorAsMinioHealthIndicator() {
        assertThat(minioHealthIndicator).isInstanceOf(MinioHealthIndicator.class);
    }
}
