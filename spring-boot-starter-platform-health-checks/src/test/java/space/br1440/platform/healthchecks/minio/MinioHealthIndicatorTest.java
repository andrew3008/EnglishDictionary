package space.br1440.platform.healthchecks.minio;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import io.minio.messages.Bucket;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.common.HealthProbe;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioHealthIndicator: unit")
class MinioHealthIndicatorTest {

    @Mock
    private MinioClient minioClient;
    @Mock
    private MinioSentinelHealthCheck sentinelHealthCheck;
    @Mock
    private MinioBucketHealthCheck bucketHealthCheck;

    private MinioHealthProperties properties;
    private MinioHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        properties = defaultProperties();
        indicator = new MinioHealthIndicator(minioClient, properties, sentinelHealthCheck, bucketHealthCheck);
    }

    @Nested
    @DisplayName("getSeverities()")
    class Severities {

        @Test
        @DisplayName("возвращает значения из properties")
        void shouldReturnSeveritiesFromProperties() {
            // Given
            properties.setProbes(EnumSet.of(HealthProbe.READINESS, HealthProbe.LIVENESS));

            // When / Then
            assertThat(indicator.getProbes())
                    .containsExactlyInAnyOrder(HealthProbe.READINESS, HealthProbe.LIVENESS);
        }

        @Test
        @DisplayName("по умолчанию содержит только READINESS")
        void shouldHaveReadinessAsDefault() {
            // Given / When / Then
            assertThat(indicator.getProbes()).containsExactly(HealthProbe.READINESS);
        }
    }

    @Nested
    @DisplayName("Connectivity mode (probeBucket не задан)")
    class ConnectivityMode {

        @Test
        @DisplayName("Status=UP, checkType='connectivity', service='MinIO' когда listBuckets возвращает пустой список")
        void shouldReturnUpWithConnectivityDetailsWhenNoBuckets() throws Exception {
            // Given
            when(minioClient.listBuckets()).thenReturn(List.of());

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("service", "MinIO")
                    .containsEntry("checkType", "connectivity")
                    .containsEntry("bucketsCount", 0)
                    .doesNotContainKey("sampleBuckets")
                    .containsKey("responseTime");
        }

        @Test
        @DisplayName("bucketsCount отражает реальное количество бакетов")
        void shouldReportCorrectBucketsCount() throws Exception {
            // Given
            List<Bucket> buckets = mockBuckets("a", "b", "c");
            when(minioClient.listBuckets()).thenReturn(buckets);

            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat(details).containsEntry("bucketsCount", 3);
        }

        @Test
        @DisplayName("sampleBuckets присутствует и содержит имена бакетов когда список непустой")
        void shouldIncludeSampleBucketsWhenBucketsExist() throws Exception {
            // Given
            List<Bucket> buckets = mockBuckets("alpha", "beta", "gamma");
            when(minioClient.listBuckets()).thenReturn(buckets);

            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat(details.get("sampleBuckets")).isInstanceOf(List.class);
            @SuppressWarnings("unchecked")
            List<String> sample = (List<String>) details.get("sampleBuckets");
            assertThat(sample).containsExactlyInAnyOrder("alpha", "beta", "gamma");
        }

        @Test
        @DisplayName("sampleBuckets ограничен 5 элементами когда бакетов больше 5")
        void shouldLimitSampleBucketsToFive() throws Exception {
            // Given — создаём список ДО вызова when(), иначе вложенный стаб нарушает Mockito
            List<Bucket> buckets = mockBuckets("b1", "b2", "b3", "b4", "b5", "b6", "b7");
            when(minioClient.listBuckets()).thenReturn(buckets);

            // When
            @SuppressWarnings("unchecked")
            List<String> sample = (List<String>) indicator.health().getDetails().get("sampleBuckets");

            // Then
            assertThat(sample).hasSize(5);
        }

        @Test
        @DisplayName("sampleBuckets отсутствует когда список бакетов пуст")
        void shouldOmitSampleBucketsWhenNoBuckets() throws Exception {
            // Given
            when(minioClient.listBuckets()).thenReturn(List.of());

            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat(details).doesNotContainKey("sampleBuckets");
        }
    }

    @Nested
    @DisplayName("BucketExists mode (probeBucket задан)")
    class BucketExistsMode {

        @Test
        @DisplayName("Status=UP, checkType='bucketExists', exists=true когда probe-бакет существует")
        void shouldReturnUpWhenProbeBucketExists() throws Exception {
            // Given
            properties.getBuckets().setProbeBucket("probe-bucket");
            when(minioClient.bucketExists(any())).thenReturn(true);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("checkType", "bucketExists")
                    .containsEntry("bucket", "probe-bucket")
                    .containsEntry("exists", true);
        }

        @Test
        @DisplayName("Status=DOWN, exists=false, error и errorType когда probe-бакет не существует")
        void shouldReturnDownWhenProbeBucketMissing() throws Exception {
            // Given
            properties.getBuckets().setProbeBucket("missing-bucket");
            when(minioClient.bucketExists(any())).thenReturn(false);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("checkType", "bucketExists")
                    .containsEntry("bucket", "missing-bucket")
                    .containsEntry("exists", false)
                    .containsKey("error")
                    .containsEntry("errorType", "IllegalStateException")
                    .containsKey("responseTime");
        }

        @Test
        @DisplayName("BucketExistsArgs передаётся с корректным именем бакета")
        void shouldPassCorrectBucketNameToBucketExistsArgs() throws Exception {
            // Given
            properties.getBuckets().setProbeBucket("exact-probe-name");
            when(minioClient.bucketExists(any())).thenReturn(true);
            ArgumentCaptor<BucketExistsArgs> captor = ArgumentCaptor.forClass(BucketExistsArgs.class);

            // When
            indicator.health();

            // Then
            verify(minioClient).bucketExists(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("exact-probe-name");
        }

        @Test
        @DisplayName("listBuckets() не вызывается в bucketExists mode")
        void shouldNotCallListBucketsInBucketExistsMode() throws Exception {
            // Given
            properties.getBuckets().setProbeBucket("probe-bucket");
            when(minioClient.bucketExists(any())).thenReturn(true);

            // When
            indicator.health();

            // Then
            verify(minioClient, never()).listBuckets();
        }
    }

    @Nested
    @DisplayName("Sentinel check delegation")
    class SentinelCheckDelegation {

        @Test
        @DisplayName("sentinelHealthCheck.check() вызывается когда sentinel.enabled=true")
        void shouldInvokeSentinelCheckWhenEnabled() throws Exception {
            // Given
            properties.getSentinelCheck().setEnabled(true);
            when(minioClient.listBuckets()).thenReturn(List.of());

            // When
            indicator.health();

            // Then
            verify(sentinelHealthCheck).check(any());
        }

        @Test
        @DisplayName("sentinelHealthCheck.check() не вызывается когда sentinel.enabled=false")
        void shouldSkipSentinelCheckWhenDisabled() throws Exception {
            // Given
            properties.getSentinelCheck().setEnabled(false);
            when(minioClient.listBuckets()).thenReturn(List.of());

            // When
            indicator.health();

            // Then
            verifyNoInteractions(sentinelHealthCheck);
        }

        @Test
        @DisplayName("details map передаётся в sentinelHealthCheck.check() — тот же экземпляр")
        void shouldPassDetailsMapToSentinelCheck() throws Exception {
            // Given
            properties.getSentinelCheck().setEnabled(true);
            when(minioClient.listBuckets()).thenReturn(List.of());
            @SuppressWarnings({"unchecked", "rawtypes"})
            ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass((Class) Map.class);

            // When
            indicator.health();

            // Then
            verify(sentinelHealthCheck).check(captor.capture());
            assertThat(captor.getValue()).containsKey("service");
        }
    }

    @Nested
    @DisplayName("BucketHealthCheck delegation")
    class BucketHealthCheckDelegation {

        @Test
        @DisplayName("bucketHealthCheck.checkBuckets() всегда вызывается в connectivity mode")
        void shouldAlwaysInvokeBucketHealthCheckInConnectivityMode() throws Exception {
            // Given
            when(minioClient.listBuckets()).thenReturn(List.of());

            // When
            indicator.health();

            // Then
            verify(bucketHealthCheck).checkBuckets(any());
        }

        @Test
        @DisplayName("bucketHealthCheck.checkBuckets() всегда вызывается в bucketExists mode")
        void shouldAlwaysInvokeBucketHealthCheckInBucketExistsMode() throws Exception {
            // Given
            properties.getBuckets().setProbeBucket("probe-bucket");
            when(minioClient.bucketExists(any())).thenReturn(true);

            // When
            indicator.health();

            // Then
            verify(bucketHealthCheck).checkBuckets(any());
        }

        @Test
        @DisplayName("bucketHealthCheck и sentinelHealthCheck НЕ вызываются когда listBuckets бросает исключение")
        void shouldNotInvokeBucketHealthCheckOnClientException() throws Exception {
            // Given
            properties.getSentinelCheck().setEnabled(true);
            when(minioClient.listBuckets()).thenThrow(new RuntimeException("connection refused"));

            // When
            indicator.health();

            // Then
            verifyNoInteractions(bucketHealthCheck);
            verifyNoInteractions(sentinelHealthCheck);
        }
    }

    @Nested
    @DisplayName("Error handling")
    class ErrorHandling {

        @Test
        @DisplayName("Status=DOWN, error=message, errorType=className когда listBuckets бросает исключение")
        void shouldReturnDownWithErrorDetailsOnListBucketsException() throws Exception {
            // Given
            when(minioClient.listBuckets()).thenThrow(new RuntimeException("connection refused"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("error", "connection refused")
                    .containsEntry("errorType", "RuntimeException")
                    .containsKey("responseTime");
        }

        @Test
        @DisplayName("Status=DOWN когда sentinelCheck.check() бросает исключение")
        void shouldReturnDownWhenSentinelCheckThrows() throws Exception {
            // Given
            properties.getSentinelCheck().setEnabled(true);
            when(minioClient.listBuckets()).thenReturn(List.of());
            doThrow(new RuntimeException("sentinel error")).when(sentinelHealthCheck).check(any());

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails())
                    .containsEntry("error", "sentinel error")
                    .containsEntry("errorType", "RuntimeException");
        }

        @Test
        @DisplayName("error=null когда exception.getMessage() возвращает null")
        void shouldHandleNullExceptionMessage() throws Exception {
            // Given
            when(minioClient.listBuckets()).thenThrow(new RuntimeException((String) null));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.DOWN);
            assertThat(health.getDetails()).containsKey("error");
        }

        @Test
        @DisplayName("responseTime присутствует в details при любом Status (UP, DOWN, OUT_OF_SERVICE)")
        void shouldIncludeResponseTimeOnEveryStatus() throws Exception {
            // Given — DOWN сценарий
            when(minioClient.listBuckets()).thenThrow(new RuntimeException("timeout"));

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getDetails()).containsKey("responseTime");
        }
    }

    private MinioHealthProperties defaultProperties() {
        MinioHealthProperties props = new MinioHealthProperties();
        props.getAsync().setEnabled(false);
        props.getSentinelCheck().setEnabled(false);
        return props;
    }

    private List<Bucket> mockBuckets(String... names) {
        return Arrays.stream(names)
                .map(MinioHealthIndicatorTest::newBucket)
                .toList();
    }

    private static Bucket newBucket(String name) {
        try {
            Bucket bucket = new Bucket();
            java.lang.reflect.Field field = Bucket.class.getDeclaredField("name");
            field.setAccessible(true);
            field.set(bucket, name);
            return bucket;
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Cannot create Bucket instance via reflection", e);
        }
    }
}
