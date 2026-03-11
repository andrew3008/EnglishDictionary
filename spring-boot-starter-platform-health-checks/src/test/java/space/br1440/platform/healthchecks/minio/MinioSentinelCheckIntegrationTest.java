package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import space.br1440.platform.healthchecks.minio.support.MinioContainerSupport;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioSentinelHealthCheck")
class MinioSentinelCheckIntegrationTest {

    @Test
    @DisplayName("status=reachable когда sentinel-объект существует")
    void shouldReportReachableWhenSentinelObjectExists() throws Exception {
        // Given
        String bucket = "sentinel-success-test";
        MinioContainerSupport.createBucket(bucket);
        MinioContainerSupport.putSentinelObject(bucket, ".health-sentinel");
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getSentinelCheck()
                    .setEnabled(true)
                    .setSentinelBucket(bucket)
                    .setSentinelObjectKey(".health-sentinel");
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            Map<String, Object> sentinelDetails = sentinelDetails(indicator);

            // Then
            assertThat(sentinelDetails)
                    .containsEntry("status", "reachable")
                    .containsEntry("sentinelBucket", bucket)
                    .containsEntry("sentinelObjectKey", ".health-sentinel")
                    .containsKey("responseTime");
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("status=sentinel_missing когда sentinel-объект не существует в бакете")
    void shouldReportSentinelMissingWhenObjectAbsent() throws Exception {
        // Given
        String bucket = "sentinel-no-object-test";
        MinioContainerSupport.createBucket(bucket);
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getSentinelCheck()
                    .setEnabled(true)
                    .setSentinelBucket(bucket)
                    .setSentinelObjectKey(".health-sentinel");
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            Map<String, Object> sentinelDetails = sentinelDetails(indicator);

            // Then
            assertThat(sentinelDetails)
                    .containsEntry("status", "sentinel_missing")
                    .containsKey("error");
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("status=sentinel_missing когда sentinel-бакет не существует")
    void shouldReportSentinelMissingWhenBucketAbsent() {
        // Given
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getSentinelCheck()
                .setEnabled(true)
                .setSentinelBucket("non-existent-bucket")
                .setSentinelObjectKey(".health-sentinel");
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // When
        Map<String, Object> sentinelDetails = sentinelDetails(indicator);

        // Then
        assertThat(sentinelDetails)
                .containsEntry("status", "sentinel_missing")
                .containsKey("error");
    }

    @Test
    @DisplayName("status=error когда Minio недоступен (сетевая ошибка)")
    void shouldReportErrorWhenMinioUnreachable() throws Exception {
        // Given
        MinioClient mockClient = mock(MinioClient.class);
        when(mockClient.statObject(any(StatObjectArgs.class)))
                .thenThrow(new RuntimeException("Connection refused"));
        MinioHealthProperties props = new MinioHealthProperties();
        props.getSentinelCheck()
                .setEnabled(true)
                .setSentinelBucket("any-bucket")
                .setSentinelObjectKey(".health-sentinel");
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(mockClient, props);

        // When
        Map<String, Object> sentinelDetails = sentinelDetails(indicator);

        // Then
        assertThat(sentinelDetails)
                .containsEntry("status", "error")
                .containsKey("error")
                .containsKey("errorType");
    }

    @Test
    @DisplayName("Ключ 'sentinelCheck' отсутствует когда enabled=false (по умолчанию)")
    void shouldNotIncludeSentinelCheckDetailsWhenDisabled() {
        // Given
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(MinioContainerSupport.baseProperties());

        // When
        var details = indicator.health().getDetails();

        // Then
        assertThat(details).doesNotContainKey("sentinelCheck");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentinelDetails(MinioHealthIndicator indicator) {
        return (Map<String, Object>) indicator.health().getDetails().get("sentinelCheck");
    }
}
