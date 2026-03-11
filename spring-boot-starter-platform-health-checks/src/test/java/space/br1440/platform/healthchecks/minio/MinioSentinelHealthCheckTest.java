package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import io.minio.errors.ErrorResponseException;
import io.minio.messages.ErrorResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioSentinelHealthCheck: unit")
class MinioSentinelHealthCheckTest {

    @Mock
    private MinioClient minioClient;
    @Mock
    private MinioHealthProperties properties;

    @InjectMocks
    private MinioSentinelHealthCheck healthCheck;

    private MinioHealthProperties.SentinelCheck sentinelConfig;

    @BeforeEach
    void setUp() {
        sentinelConfig = enabledConfig("sentinel-bucket", ".health-sentinel");
        when(properties.getSentinelCheck()).thenReturn(sentinelConfig);
    }

    @Nested
    @DisplayName("когда sentinel выключен")
    class WhenDisabled {

        @Test
        @DisplayName("details не изменяются, MinioClient не вызывается")
        void disabledLeavesDetailsUnchanged() {
            // Given
            sentinelConfig.setEnabled(false);
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(details).isEmpty();
            verifyNoInteractions(minioClient);
        }
    }

    @Nested
    @DisplayName("когда sentinel-объект доступен")
    class WhenReachable {

        @BeforeEach
        void stubSuccess() throws Exception {
            when(minioClient.statObject(any())).thenReturn(mock(StatObjectResponse.class));
        }

        @Test
        @DisplayName("status='reachable'")
        void statusIsReachable() {
            // Given
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details)).containsEntry("status", "reachable");
        }

        @Test
        @DisplayName("sentinelCheck содержит bucket, objectKey и responseTime")
        void mandatoryFieldsPresent() {
            // Given
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details))
                    .containsEntry("sentinelBucket", "sentinel-bucket")
                    .containsEntry("sentinelObjectKey", ".health-sentinel")
                    .containsKey("responseTime");
        }

        @Test
        @DisplayName("error и errorType отсутствуют при успешной проверке")
        void noErrorFieldsOnSuccess() {
            // Given
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details))
                    .doesNotContainKey("error")
                    .doesNotContainKey("errorType");
        }

        @Test
        @DisplayName("responseTime имеет формат '\\d+ms'")
        void responseTimeFormat() {
            // Given
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details).get("responseTime"))
                    .isInstanceOf(String.class)
                    .asString()
                    .matches("\\d+ms");
        }
    }

    @Nested
    @DisplayName("когда sentinel-объект не найден")
    class WhenSentinelMissing {

        @Test
        @DisplayName("NoSuchKey → status='sentinel_missing', error присутствует")
        void noSuchKeyReportedAsSentinelMissing() throws Exception {
            // Given
            stubErrorResponse("NoSuchKey");
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details))
                    .containsEntry("status", "sentinel_missing")
                    .containsKey("error")
                    .doesNotContainKey("errorType");
        }

        @Test
        @DisplayName("NoSuchBucket → status='sentinel_missing', error присутствует")
        void noSuchBucketReportedAsSentinelMissing() throws Exception {
            // Given
            stubErrorResponse("NoSuchBucket");
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details))
                    .containsEntry("status", "sentinel_missing")
                    .containsKey("error");
        }

        @Test
        @DisplayName("error содержит bucket и objectKey из конфигурации")
        void errorMessageContainsBucketAndKey() throws Exception {
            // Given
            stubErrorResponse("NoSuchKey");
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details).get("error"))
                    .asString()
                    .contains("sentinel-bucket", ".health-sentinel");
        }
    }

    @Nested
    @DisplayName("когда MinioClient бросает неожиданное исключение")
    class WhenClientThrowsUnexpected {

        @Test
        @DisplayName("status='error', error и errorType присутствуют")
        void unexpectedExceptionReportedAsError() throws Exception {
            // Given
            when(minioClient.statObject(any())).thenThrow(new RuntimeException("connection refused"));
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details))
                    .containsEntry("status", "error")
                    .containsEntry("error", "connection refused")
                    .containsEntry("errorType", "RuntimeException");
        }

        @Test
        @DisplayName("responseTime присутствует даже при exception")
        void responseTimePresentOnException() throws Exception {
            // Given
            when(minioClient.statObject(any())).thenThrow(new RuntimeException("timeout"));
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details)).containsKey("responseTime");
        }

        @Test
        @DisplayName("ErrorResponseException с кодом не NoSuchKey/NoSuchBucket → status='error'")
        void otherErrorResponseCodeReportedAsError() throws Exception {
            // Given
            stubErrorResponse("AccessDenied");
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(sentinelDetails(details)).containsEntry("status", "error");
        }
    }

    @Nested
    @DisplayName("корректность аргументов переданных в MinioClient")
    class ArgumentVerification {

        @Test
        @DisplayName("statObject вызывается с bucket и objectKey из конфигурации")
        void correctArgsPassedToStatObject() throws Exception {
            // Given
            when(minioClient.statObject(any())).thenReturn(mock(StatObjectResponse.class));
            ArgumentCaptor<StatObjectArgs> captor = ArgumentCaptor.forClass(StatObjectArgs.class);

            // When
            healthCheck.check(new HashMap<>());

            // Then
            verify(minioClient).statObject(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("sentinel-bucket");
            assertThat(captor.getValue().object()).isEqualTo(".health-sentinel");
        }

        @Test
        @DisplayName("sentinelCheck помещается в details под ключом 'sentinelCheck'")
        void resultStoredUnderCorrectKey() throws Exception {
            // Given
            when(minioClient.statObject(any())).thenReturn(mock(StatObjectResponse.class));
            Map<String, Object> details = new HashMap<>();

            // When
            healthCheck.check(details);

            // Then
            assertThat(details).containsKey("sentinelCheck");
            assertThat(details.get("sentinelCheck")).isInstanceOf(Map.class);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> sentinelDetails(Map<String, Object> details) {
        return (Map<String, Object>) details.get("sentinelCheck");
    }

    private MinioHealthProperties.SentinelCheck enabledConfig(String bucket, String objectKey) {
        MinioHealthProperties.SentinelCheck config = new MinioHealthProperties.SentinelCheck();
        config.setEnabled(true);
        config.setSentinelBucket(bucket);
        config.setSentinelObjectKey(objectKey);
        return config;
    }

    private void stubErrorResponse(String errorCode) throws Exception {
        ErrorResponse errorResponse = mock(ErrorResponse.class);
        when(errorResponse.code()).thenReturn(errorCode);
        ErrorResponseException exception = mock(ErrorResponseException.class);
        when(exception.errorResponse()).thenReturn(errorResponse);
        when(minioClient.statObject(any())).thenThrow(exception);
    }
}
