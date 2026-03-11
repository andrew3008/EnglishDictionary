package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import io.minio.messages.Bucket;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("HealthCheckTimer: maxResponse threshold — OUT_OF_SERVICE")
class MinioHealthTimeoutTest {

    private static final int CALL_DELAY_MS = 50;
    private static final long TIGHT_LIMIT_MS = 10;
    private static final long LOOSE_LIMIT_S = 10;

    @Mock
    private MinioClient minioClient;
    @Mock
    private MinioBucketHealthCheck bucketHealthCheck;
    @Mock
    private MinioSentinelHealthCheck operationsHealthCheck;

    @Test
    @DisplayName("Status.OUT_OF_SERVICE когда responseTime > maxResponse")
    void shouldReturnOutOfServiceWhenResponseTimeExceedsMaxResponse() throws Exception {
        // Given
        stubSlowListBuckets();
        MinioHealthProperties props = propsWithMaxResponse(Duration.ofMillis(TIGHT_LIMIT_MS));

        // When
        Health health = indicator(props).health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.OUT_OF_SERVICE);
    }

    @Test
    @DisplayName("Details содержат 'warning', 'threshold', 'exceeded' когда порог превышен")
    void shouldIncludeTimeoutDetailsWhenThresholdExceeded() throws Exception {
        // Given
        stubSlowListBuckets();
        MinioHealthProperties props = propsWithMaxResponse(Duration.ofMillis(TIGHT_LIMIT_MS));

        // When
        var details = indicator(props).health().getDetails();

        // Then
        assertThat(details)
                .containsKey("warning")
                .containsKey("threshold")
                .containsKey("exceeded");
        assertThat(details.get("warning")).asString().isNotBlank();
        assertThat(details.get("threshold")).asString().matches("^(\\d+ms|\\d+\\.\\d+s)$");
        assertThat(details.get("exceeded")).asString().matches("^(\\d+ms|\\d+\\.\\d+s)$");
    }

    @Test
    @DisplayName("Status.UP и timeout-ключи отсутствуют когда responseTime <= maxResponse")
    void shouldReturnUpAndOmitTimeoutKeysWhenWithinMaxResponse() throws Exception {
        // Given
        when(minioClient.listBuckets()).thenReturn(List.of());
        MinioHealthProperties props = propsWithMaxResponse(Duration.ofSeconds(LOOSE_LIMIT_S));

        // When
        Health health = indicator(props).health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails())
                .doesNotContainKey("warning")
                .doesNotContainKey("threshold")
                .doesNotContainKey("exceeded");
    }

    private MinioHealthIndicator indicator(MinioHealthProperties props) {
        return new MinioHealthIndicator(minioClient, props, operationsHealthCheck, bucketHealthCheck);
    }

    private void stubSlowListBuckets() throws Exception {
        when(minioClient.listBuckets()).thenAnswer(inv -> {
            try {
                Thread.sleep(CALL_DELAY_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return List.<Bucket>of();
        });
    }

    private static MinioHealthProperties propsWithMaxResponse(Duration maxResponse) {
        MinioHealthProperties props = new MinioHealthProperties();
        props.getAsync().setEnabled(false);
        props.getTimeout().setHealthCheckTimeout(maxResponse);
        return props;
    }
}
