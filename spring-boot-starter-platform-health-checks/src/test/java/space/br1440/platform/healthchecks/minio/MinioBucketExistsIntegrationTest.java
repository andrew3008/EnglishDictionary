package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.minio.support.MinioContainerSupport;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MinioHealthIndicator: bucketExists mode")
class MinioBucketExistsIntegrationTest {

    @Test
    @DisplayName("UP, checkType='bucketExists', exists=true когда healthCheck-бакет существует")
    void shouldReturnUpWhenHealthCheckBucketExists() throws Exception {
        String bucket = "hc-exists-bucket";
        MinioContainerSupport.createBucket(bucket);
        try {
            // Given
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setProbeBucket(bucket);
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("checkType", "bucketExists")
                    .containsEntry("bucket", bucket)
                    .containsEntry("exists", true);
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("DOWN, checkType='bucketExists', exists=false, error когда бакет не существует")
    void shouldReturnDownWhenHealthCheckBucketDoesNotExist() {
        // Given
        String missingBucket = "hc-non-existent-bucket";
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getBuckets().setProbeBucket(missingBucket);
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // When
        Health health = indicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsEntry("checkType", "bucketExists")
                .containsEntry("bucket", missingBucket)
                .containsEntry("exists", false)
                .containsKey("error");
    }

    @Test
    @DisplayName("DOWN + error + errorType когда MinIO server недоступен")
    void shouldReturnDownWhenMinioIsUnreachable() {
        // Given
        MinioClient badClient = MinioClient.builder()
                .endpoint("http://localhost:19999")
                .credentials("bad-key", "bad-secret")
                .build();
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(badClient, new MinioHealthProperties());

        // When
        Health health = indicator.health();

        // Then
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails())
                .containsKey("error")
                .containsKey("errorType");
    }

    @Test
    @DisplayName("details в bucketExists mode содержат service=MinIO")
    void shouldIncludeServiceMinioInDetailsForBucketExistsMode() throws Exception {
        String bucket = "hc-service-details-bucket";
        MinioContainerSupport.createBucket(bucket);
        try {
            // Given
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setProbeBucket(bucket);
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsEntry("service", "MinIO")
                    .containsEntry("checkType", "bucketExists")
                    .containsEntry("bucket", bucket);
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("details в bucketExists mode содержат responseTime")
    void shouldIncludeResponseTimeInBucketExistsMode() throws Exception {
        String bucket = "hc-responsetime-bucket";
        MinioContainerSupport.createBucket(bucket);
        try {
            // Given
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setProbeBucket(bucket);
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            Health health = indicator.health();

            // Then
            assertThat(health.getStatus()).isEqualTo(Status.UP);
            assertThat(health.getDetails())
                    .containsKey("responseTime")
                    .containsEntry("checkType", "bucketExists");
            assertThat(health.getDetails().get("responseTime")).isInstanceOf(String.class);
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("UP после создания бакета (DOWN → create bucket → UP)")
    void shouldReturnUpAfterBucketCreated() throws Exception {
        String bucket = "hc-state-transition-bucket";
        // Given — probeBucket указывает на ещё несуществующий бакет
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getBuckets().setProbeBucket(bucket);
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // When — первый вызов: бакета нет
        Health healthBefore = indicator.health();
        // Then
        assertThat(healthBefore.getStatus()).isEqualTo(Status.DOWN);
        assertThat(healthBefore.getDetails())
                .containsEntry("checkType", "bucketExists")
                .containsEntry("bucket", bucket)
                .containsEntry("exists", false);

        // When — создаём бакет и повторно запрашиваем health
        MinioContainerSupport.createBucket(bucket);
        try {
            Health healthAfter = indicator.health();
            // Then
            assertThat(healthAfter.getStatus()).isEqualTo(Status.UP);
            assertThat(healthAfter.getDetails())
                    .containsEntry("checkType", "bucketExists")
                    .containsEntry("bucket", bucket)
                    .containsEntry("exists", true);
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }
}
