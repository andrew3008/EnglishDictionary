package space.br1440.platform.healthchecks.minio;

import io.minio.BucketExistsArgs;
import io.minio.MinioClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("MinioBucketHealthCheck: unit")
class MinioBucketHealthCheckTest {

    @Mock
    private MinioClient minioClient;

    private MinioHealthProperties properties;
    private MinioBucketHealthCheck healthCheck;

    @BeforeEach
    void setUp() {
        properties = new MinioHealthProperties();
        healthCheck = new MinioBucketHealthCheck(minioClient, properties);
    }

    private void setMonitoredBuckets(String... names) {
        properties.getBuckets().setMonitoredBuckets(Arrays.asList(names));
    }

    @Nested
    @DisplayName("когда список монitoredBuckets пуст или null")
    class WhenNoMonitoredBuckets {

        @Test
        @DisplayName("null список → details не изменяются")
        void nullListLeavesDetailsUnchanged() {
            properties.getBuckets().setMonitoredBuckets(null);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).isEmpty();
            verifyNoInteractions(minioClient);
        }

        @Test
        @DisplayName("пустой список → details не изменяются")
        void emptyListLeavesDetailsUnchanged() {
            setMonitoredBuckets();
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).isEmpty();
            verifyNoInteractions(minioClient);
        }
    }

    @Nested
    @DisplayName("когда все бакеты существуют")
    class WhenAllBucketsExist {

        @Test
        @DisplayName("exists=true, accessible=true для каждого бакета")
        void allBucketsReportedAsExistingAndAccessible() throws Exception {
            setMonitoredBuckets("bucket-a", "bucket-b");
            when(minioClient.bucketExists(any())).thenReturn(true);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).containsKey("buckets");
            Map<String, Object> buckets = bucketsMap(details);

            assertBucketInfo(buckets, "bucket-a", true, true, false);
            assertBucketInfo(buckets, "bucket-b", true, true, false);
        }

        @Test
        @DisplayName("bucketsWarning отсутствует когда все бакеты доступны")
        void noWarningWhenAllBucketsAccessible() throws Exception {
            setMonitoredBuckets("bucket-a");
            when(minioClient.bucketExists(any())).thenReturn(true);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).doesNotContainKey("bucketsWarning");
        }

        @Test
        @DisplayName("порядок бакетов в результате соответствует порядку в конфигурации")
        void bucketOrderPreserved() throws Exception {
            setMonitoredBuckets("first", "second", "third");
            when(minioClient.bucketExists(any())).thenReturn(true);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(bucketsMap(details).keySet())
                    .containsExactly("first", "second", "third");
        }
    }

    @Nested
    @DisplayName("когда часть бакетов не существует")
    class WhenSomeBucketsMissing {

        @Test
        @DisplayName("несуществующий бакет: exists=false, accessible=false, warning присутствует")
        void missingBucketReportedWithWarning() throws Exception {
            setMonitoredBuckets("missing");
            when(minioClient.bucketExists(any())).thenReturn(false);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            Map<String, Object> buckets = bucketsMap(details);
            assertBucketInfo(buckets, "missing", false, false, true);
            assertThat(bucketInfo(buckets, "missing"))
                    .containsEntry("warning", "Bucket does not exist: missing");
        }

        @Test
        @DisplayName("bucketsWarning присутствует когда хотя бы один бакет недоступен")
        void warningPresentWhenAnyBucketMissing() throws Exception {
            setMonitoredBuckets("exists", "missing");
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("exists")))).thenReturn(true);
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("missing")))).thenReturn(false);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).containsEntry("bucketsWarning", "One or more monitored buckets are unavailable");
        }

        @Test
        @DisplayName("существующий бакет рядом с несуществующим остаётся healthy")
        void existingBucketUnaffectedByMissingNeighbour() throws Exception {
            setMonitoredBuckets("exists", "missing");
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("exists")))).thenReturn(true);
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("missing")))).thenReturn(false);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertBucketInfo(bucketsMap(details), "exists", true, true, false);
        }
    }

    @Nested
    @DisplayName("когда MinioClient бросает исключение")
    class WhenClientThrows {

        @Test
        @DisplayName("exception → exists=false, accessible=false, warning=сообщение исключения")
        void exceptionReportedAsInaccessible() throws Exception {
            setMonitoredBuckets("broken");
            when(minioClient.bucketExists(any())).thenThrow(new RuntimeException("connection refused"));
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            Map<String, Object> info = bucketInfo(bucketsMap(details), "broken");
            assertThat(info)
                    .containsEntry("exists", false)
                    .containsEntry("accessible", false)
                    .containsEntry("warning", "connection refused");
        }

        @Test
        @DisplayName("exception в одном бакете не прерывает проверку остальных")
        void exceptionInOneBucketDoesNotAbortOthers() throws Exception {
            setMonitoredBuckets("broken", "healthy");
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("broken"))))
                    .thenThrow(new RuntimeException("timeout"));
            when(minioClient.bucketExists(argThat(args -> args != null && args.bucket().equals("healthy"))))
                    .thenReturn(true);
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            Map<String, Object> buckets = bucketsMap(details);
            assertThat(buckets).containsKeys("broken", "healthy");
            assertBucketInfo(buckets, "healthy", true, true, false);
        }

        @Test
        @DisplayName("bucketsWarning присутствует когда клиент бросает исключение")
        void warningPresentOnException() throws Exception {
            setMonitoredBuckets("broken");
            when(minioClient.bucketExists(any())).thenThrow(new RuntimeException("error"));
            Map<String, Object> details = new HashMap<>();

            healthCheck.checkBuckets(details);

            assertThat(details).containsKey("bucketsWarning");
        }
    }

    @Nested
    @DisplayName("корректность аргументов переданных в MinioClient")
    class ArgumentVerification {

        @Test
        @DisplayName("bucketExists вызывается с именем из конфигурации")
        void correctBucketNamePassedToClient() throws Exception {
            setMonitoredBuckets("exact-name");
            when(minioClient.bucketExists(any())).thenReturn(true);
            ArgumentCaptor<BucketExistsArgs> captor = ArgumentCaptor.forClass(BucketExistsArgs.class);

            healthCheck.checkBuckets(new HashMap<>());

            verify(minioClient).bucketExists(captor.capture());
            assertThat(captor.getValue().bucket()).isEqualTo("exact-name");
        }

        @Test
        @DisplayName("bucketExists вызывается ровно по одному разу на каждый бакет")
        void clientCalledOncePerBucket() throws Exception {
            setMonitoredBuckets("bucket-one", "bucket-two", "bucket-three");
            when(minioClient.bucketExists(any())).thenReturn(true);

            healthCheck.checkBuckets(new HashMap<>());

            verify(minioClient, times(3)).bucketExists(any());
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bucketsMap(Map<String, Object> details) {
        return (Map<String, Object>) details.get("buckets");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> bucketInfo(Map<String, Object> bucketsMap, String name) {
        return (Map<String, Object>) bucketsMap.get(name);
    }

    private void assertBucketInfo(Map<String, Object> bucketsMap,
                                  String name,
                                  boolean exists,
                                  boolean accessible,
                                  boolean hasWarning) {
        Map<String, Object> info = bucketInfo(bucketsMap, name);
        assertThat(info)
                .containsEntry("exists", exists)
                .containsEntry("accessible", accessible);
        if (hasWarning) {
            assertThat(info).containsKey("warning");
        } else {
            assertThat(info).doesNotContainKey("warning");
        }
    }
}
