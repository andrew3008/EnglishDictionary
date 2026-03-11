package space.br1440.platform.healthchecks.minio;

import io.minio.MinioClient;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.minio.support.MinioContainerSupport;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioHealthIndicator: listBuckets mode")
class MinioListBucketsIntegrationTest {

    private final MinioHealthProperties properties = MinioContainerSupport.baseProperties();
    private final MinioHealthIndicator indicator = MinioContainerSupport.indicator(properties);

    @Test
    @DisplayName("Статус UP при доступном MinIO")
    void shouldReturnUpStatus() {
        // When
        var status = indicator.health().getStatus();

        // Then
        assertThat(status).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("Details содержат service='MinIO' и checkType='connectivity' в режиме listBuckets")
    void shouldIncludeServiceAndCheckTypeInConnectivityMode() {
        // When
        var details = indicator.health().getDetails();

        // Then
        assertThat(details).containsEntry("service", "MinIO");
        assertThat(details).containsEntry("checkType", "connectivity");
    }

    @Test
    @DisplayName("checkType='connectivity', bucketsCount присутствует и >= 0")
    void shouldUseListBucketsCheckType() {
        // When
        var details = indicator.health().getDetails();

        // Then
        assertThat(details).containsEntry("checkType", "connectivity");
        assertThat(details.get("bucketsCount")).isInstanceOf(Integer.class);
        assertThat((Integer) details.get("bucketsCount")).isGreaterThanOrEqualTo(0);
    }

    @Test
    @DisplayName("responseTime присутствует в формате: NNms | N.NNs | Nm Ns")
    void shouldIncludeFormattedResponseTime() {
        // When
        String responseTime = (String) indicator.health().getDetails().get("responseTime");

        // Then
        assertThat(responseTime)
                .isNotBlank()
                .matches("^(\\d+ms|\\d+\\.\\d+s|\\d+m \\d+s)$");
    }

    @Test
    @DisplayName("sampleBuckets присутствует (≤ 5 элементов) когда в MinIO есть бакеты")
    void shouldIncludeSampleBucketsWhenBucketsExist() throws Exception {
        // Given
        MinioContainerSupport.createBucket("list-sample-test");
        try {
            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat((Integer) details.get("bucketsCount")).isGreaterThanOrEqualTo(1);

            @SuppressWarnings("unchecked")
            List<String> sample = (List<String>) details.get("sampleBuckets");
            assertThat(sample)
                    .isNotEmpty()
                    .hasSizeLessThanOrEqualTo(5);
        } finally {
            MinioContainerSupport.removeBucket("list-sample-test");
        }
    }

    @Test
    @DisplayName("sampleBuckets отсутствует когда список бакетов пуст")
    void shouldNotIncludeSampleBucketsWhenNoBuckets() throws Exception {
        // Given
        MinioClient emptyClient = mock(MinioClient.class);
        when(emptyClient.listBuckets()).thenReturn(List.of());
        MinioHealthIndicator emptyIndicator = MinioContainerSupport.indicator(emptyClient, new MinioHealthProperties());

        // When
        var details = emptyIndicator.health().getDetails();

        // Then
        assertThat(details).containsEntry("bucketsCount", 0);
        assertThat(details).doesNotContainKey("sampleBuckets");
    }

    @Test
    @DisplayName("MinioClient.listBuckets() возвращает список без исключений")
    void shouldListBucketsViaClientDirectly() throws Exception {
        // When
        var buckets = MinioContainerSupport.CLIENT.listBuckets();

        // Then
        assertThat(buckets).isNotNull();
    }

    @Test
    @DisplayName("sampleBuckets ограничен 5 элементами при большем числе бакетов")
    void shouldLimitSampleBucketsToFive() throws Exception {
        // Given — создаём 7 бакетов с уникальным префиксом
        String prefix = "list-limit-five-";
        List<String> names = List.of(
                prefix + "1", prefix + "2", prefix + "3", prefix + "4",
                prefix + "5", prefix + "6", prefix + "7"
        );
        for (String name : names) {
            MinioContainerSupport.createBucket(name);
        }
        try {
            // When
            var details = indicator.health().getDetails();

            // Then — bucketsCount >= 7 (могут быть бакеты от других тестов в shared container),
            //        sampleBuckets всегда ровно 5 (limit задан в продакшн-коде)
            assertThat((Integer) details.get("bucketsCount")).isGreaterThanOrEqualTo(7);
            @SuppressWarnings("unchecked")
            List<String> sample = (List<String>) details.get("sampleBuckets");
            assertThat(sample).hasSize(5);
        } finally {
            for (String name : names) {
                MinioContainerSupport.removeBucket(name);
            }
        }
    }

    @Test
    @DisplayName("после недоступности MinIO при восстановлении доступа статус снова UP")
    void shouldReturnUpWhenMinioRestartsAfterFailure() {
        // Given — клиент к недоступному MinIO
        MinioClient unreachableClient = MinioClient.builder()
                .endpoint("http://localhost:19999")
                .credentials("bad-key", "bad-secret")
                .build();
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        MinioHealthIndicator unreachableIndicator = MinioContainerSupport.indicator(unreachableClient, props);

        // When — первый вызов: MinIO недоступен
        var statusBefore = unreachableIndicator.health().getStatus();
        // Then
        assertThat(statusBefore).isEqualTo(Status.DOWN);

        // When — проверка через работающий клиент (имитация восстановления / restart)
        MinioHealthIndicator recoveryIndicator = MinioContainerSupport.indicator(MinioContainerSupport.CLIENT, props);
        var statusAfter = recoveryIndicator.health().getStatus();
        // Then
        assertThat(statusAfter).isEqualTo(Status.UP);
    }
}
