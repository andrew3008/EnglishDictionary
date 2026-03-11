package space.br1440.platform.healthchecks.minio;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Status;
import space.br1440.platform.healthchecks.minio.support.MinioContainerSupport;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("MinioBucketHealthCheck: toCheck bucket monitoring")
class MinioBucketMonitoringIntegrationTest {

    @Test
    @DisplayName("Ключ 'buckets' отсутствует когда toCheck не задан")
    void shouldNotIncludeBucketsKeyWhenToCheckIsEmpty() {
        // Given
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(MinioContainerSupport.baseProperties());

        // When
        var details = indicator.health().getDetails();

        // Then
        assertThat(details).doesNotContainKey("buckets");
    }

    @Test
    @DisplayName("exists=true, accessible=true, warning отсутствует для существующего бакета")
    void shouldReportExistingBucketAsAccessible() throws Exception {
        // Given
        String bucket = "monitoring-exists-test";
        MinioContainerSupport.createBucket(bucket);
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setMonitoredBuckets(List.of(bucket));
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat(details).containsKey("buckets");
            @SuppressWarnings("unchecked")
            Map<String, Object> bucketsMap = (Map<String, Object>) details.get("buckets");
            @SuppressWarnings("unchecked")
            Map<String, Object> info = (Map<String, Object>) bucketsMap.get(bucket);

            assertThat(info)
                    .containsEntry("exists", true)
                    .containsEntry("accessible", true)
                    .doesNotContainKey("warning");
            assertThat(details).doesNotContainKey("bucketsWarning");
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }

    @Test
    @DisplayName("exists=false, accessible=false, warning и bucketsWarning когда бакет отсутствует")
    void shouldReportMissingBucketWithWarning() {
        // Given
        String missingBucket = "monitoring-missing-test";
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getBuckets().setMonitoredBuckets(List.of(missingBucket));
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // When
        var details = indicator.health().getDetails();

        // Then
        assertThat(details).containsKey("buckets").containsKey("bucketsWarning");
        @SuppressWarnings("unchecked")
        Map<String, Object> bucketsMap = (Map<String, Object>) details.get("buckets");
        @SuppressWarnings("unchecked")
        Map<String, Object> info = (Map<String, Object>) bucketsMap.get(missingBucket);

        assertThat(info)
                .containsEntry("exists", false)
                .containsEntry("accessible", false)
                .containsKey("warning");
    }

    @Test
    @DisplayName("Несколько бакетов: один существует, один нет — оба в details, bucketsWarning присутствует")
    void shouldHandleMultipleBucketsWithMixedExistence() throws Exception {
        // Given
        String existing = "monitoring-multi-exists";
        String missing  = "monitoring-multi-missing";
        MinioContainerSupport.createBucket(existing);
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setMonitoredBuckets(List.of(existing, missing));
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            var details = indicator.health().getDetails();

            // Then
            @SuppressWarnings("unchecked")
            Map<String, Object> bucketsMap = (Map<String, Object>) details.get("buckets");
            assertThat(bucketsMap).containsKeys(existing, missing);

            @SuppressWarnings("unchecked")
            Map<String, Object> existingInfo = (Map<String, Object>) bucketsMap.get(existing);
            assertThat(existingInfo)
                    .containsEntry("exists", true)
                    .containsEntry("accessible", true);

            @SuppressWarnings("unchecked")
            Map<String, Object> missingInfo = (Map<String, Object>) bucketsMap.get(missing);
            assertThat(missingInfo)
                    .containsEntry("exists", false)
                    .containsEntry("accessible", false)
                    .containsKey("warning");

            assertThat(details).containsKey("bucketsWarning");
        } finally {
            MinioContainerSupport.removeBucket(existing);
        }
    }

    @Test
    @DisplayName("Несколько бакетов: все существуют — bucketsWarning отсутствует")
    void shouldNotAddBucketsWarningWhenAllBucketsExist() throws Exception {
        // Given
        String bucket1 = "monitoring-all-exist-1";
        String bucket2 = "monitoring-all-exist-2";
        MinioContainerSupport.createBucket(bucket1);
        MinioContainerSupport.createBucket(bucket2);
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setMonitoredBuckets(List.of(bucket1, bucket2));
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            var details = indicator.health().getDetails();

            // Then
            assertThat(details).doesNotContainKey("bucketsWarning");
        } finally {
            MinioContainerSupport.removeBucket(bucket1);
            MinioContainerSupport.removeBucket(bucket2);
        }
    }

    @Test
    @DisplayName("Статус остаётся UP даже когда мониторируемый бакет отсутствует")
    void shouldRemainUpEvenWhenMonitoredBucketIsMissing() {
        // Given
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getBuckets().setMonitoredBuckets(List.of("soft-warning-missing-bucket"));

        // When
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // Then
        assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
    }

    @Test
    @DisplayName("порядок бакетов в details совпадает с порядком в monitoredBuckets (LinkedHashMap)")
    void shouldPreserveBucketOrderInDetailsMap() throws Exception {
        // Given — три бакета в фиксированном порядке (два существуют, один нет — порядок всё равно сохраняется)
        String first = "monitoring-order-first";
        String second = "monitoring-order-second";
        String third = "monitoring-order-missing";
        MinioContainerSupport.createBucket(first);
        MinioContainerSupport.createBucket(second);
        try {
            MinioHealthProperties props = MinioContainerSupport.baseProperties();
            props.getBuckets().setMonitoredBuckets(List.of(first, second, third));
            MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

            // When
            var details = indicator.health().getDetails();

            // Then — порядок ключей в details.buckets соответствует порядку в конфигурации
            assertThat(details).containsKey("buckets");
            @SuppressWarnings("unchecked")
            Map<String, Object> bucketsMap = (Map<String, Object>) details.get("buckets");
            assertThat(bucketsMap.keySet())
                    .containsExactly(first, second, third);
        } finally {
            MinioContainerSupport.removeBucket(first);
            MinioContainerSupport.removeBucket(second);
        }
    }

    @Test
    @DisplayName("после создания бакета следующий health check показывает exists=true")
    void shouldHandleBucketCreatedAfterInitialCheck() throws Exception {
        String bucket = "monitoring-created-after-check";
        // Given — мониторируемый бакет изначально отсутствует
        MinioHealthProperties props = MinioContainerSupport.baseProperties();
        props.getBuckets().setMonitoredBuckets(List.of(bucket));
        MinioHealthIndicator indicator = MinioContainerSupport.indicator(props);

        // When — первый вызов: бакета ещё нет
        var detailsBefore = indicator.health().getDetails();
        
        // Then
        assertThat(detailsBefore).containsKey("buckets");
        @SuppressWarnings("unchecked")
        Map<String, Object> bucketsMapBefore = (Map<String, Object>) detailsBefore.get("buckets");
        @SuppressWarnings("unchecked")
        Map<String, Object> infoBefore = (Map<String, Object>) bucketsMapBefore.get(bucket);
        assertThat(infoBefore)
                .containsEntry("exists", false)
                .containsEntry("accessible", false);

        // When — создаём бакет и повторно запрашиваем health
        MinioContainerSupport.createBucket(bucket);
        try {
            var detailsAfter = indicator.health().getDetails();
            // Then
            assertThat(detailsAfter).containsKey("buckets");
            @SuppressWarnings("unchecked")
            Map<String, Object> bucketsMapAfter = (Map<String, Object>) detailsAfter.get("buckets");
            @SuppressWarnings("unchecked")
            Map<String, Object> infoAfter = (Map<String, Object>) bucketsMapAfter.get(bucket);
            assertThat(infoAfter)
                    .containsEntry("exists", true)
                    .containsEntry("accessible", true)
                    .doesNotContainKey("warning");
        } finally {
            MinioContainerSupport.removeBucket(bucket);
        }
    }
}
