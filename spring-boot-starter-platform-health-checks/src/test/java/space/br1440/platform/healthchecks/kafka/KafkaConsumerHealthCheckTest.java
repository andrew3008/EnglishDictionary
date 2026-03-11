package space.br1440.platform.healthchecks.kafka;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListConsumerGroupOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.KafkaFuture;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@DisplayName("KafkaConsumerHealthCheck")
class KafkaConsumerHealthCheckTest {

    private static final String GROUP_ID = "payments-consumer-group";
    private static final TopicPartition PARTITION_0 = new TopicPartition("payments", 0);
    private static final TopicPartition PARTITION_1 = new TopicPartition("payments", 1);
    private static final TopicPartition PARTITION_2 = new TopicPartition("payments", 2);

    @Mock
    private AdminClient adminClient;

    private KafkaConsumerHealthCheck check;

    @BeforeEach
    void setUp() {
        KafkaHealthProperties properties = new KafkaHealthProperties();
        properties.getConsumer().setEnabled(true).setGroupId(GROUP_ID);
        check = new KafkaConsumerHealthCheck(properties);
    }

    @Nested
    @DisplayName("status=reachable")
    class Reachable {

        @Test
        @DisplayName("status=reachable, totalLag=0, assignedPartitions=1 когда consumer догнал log-end-offset")
        void shouldReportReachableWithZeroLag() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(100L)),
                    Map.of(PARTITION_0, endOffset(100L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("totalLag", 0L)
                    .containsEntry("assignedPartitions", 1);
        }

        @Test
        @DisplayName("totalLag > 0 не влияет на статус — lag является нормой при нагрузке")
        void shouldReportReachableEvenWithPositiveLag() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(50L)),
                    Map.of(PARTITION_0, endOffset(150L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("totalLag", 100L);
        }

        @Test
        @DisplayName("totalLag суммируется по всем партициям")
        void shouldSumLagAcrossPartitions() {
            // Given
            mockOffsets(
                    Map.of(
                            PARTITION_0, committed(10L),
                            PARTITION_1, committed(20L),
                            PARTITION_2, committed(30L)
                    ),
                    Map.of(
                            PARTITION_0, endOffset(30L),   // lag=20
                            PARTITION_1, endOffset(45L),   // lag=25
                            PARTITION_2, endOffset(55L)    // lag=25
                    )
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("totalLag", 70L)
                    .containsEntry("assignedPartitions", 3);
        }

        @Test
        @DisplayName("assignedPartitions соответствует числу партиций с committed offsets")
        void shouldReportCorrectAssignedPartitionCount() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(0L), PARTITION_1, committed(0L)),
                    Map.of(PARTITION_0, endOffset(0L), PARTITION_1, endOffset(0L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer).containsEntry("assignedPartitions", 2);
        }

        @Test
        @DisplayName("lag не уходит в отрицательные значения когда endOffset < committed (rewind-сценарий)")
        void shouldClampLagToZeroWhenEndOffsetBehindCommitted() {
            // Given — endOffset < committed возможен при ручном reset или compact-топике
            mockOffsets(
                    Map.of(PARTITION_0, committed(100L)),
                    Map.of(PARTITION_0, endOffset(80L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("totalLag", 0L);
        }

        @Test
        @DisplayName("lag=0 когда партиция отсутствует в endOffsets (fallback на committed offset)")
        void shouldFallbackToZeroLagWhenPartitionMissingFromEndOffsets() {
            // Given — listOffsets вернул частичный результат без PARTITION_0
            mockOffsets(
                    Map.of(PARTITION_0, committed(50L), PARTITION_1, committed(10L)),
                    Map.of(PARTITION_1, endOffset(20L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then — PARTITION_0 lag = 0 (fallback), PARTITION_1 lag = 10
            assertThat(consumer)
                    .containsEntry("status", "reachable")
                    .containsEntry("totalLag", 10L);
        }

        @Test
        @DisplayName("groupId попадает в details")
        void shouldIncludeGroupIdInDetails() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(0L)),
                    Map.of(PARTITION_0, endOffset(0L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer).containsEntry("groupId", GROUP_ID);
        }
    }

    @Nested
    @DisplayName("status=group_not_found")
    class GroupNotFound {

        @Test
        @DisplayName("status=group_not_found когда listConsumerGroupOffsets возвращает пустую map")
        void shouldReportGroupNotFoundWhenNoCommittedOffsets() {
            // Given
            mockCommittedOffsets(Map.of());

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "group_not_found")
                    .containsEntry("groupId", GROUP_ID)
                    .containsKey("error");
        }

        @Test
        @DisplayName("error-message содержит groupId")
        void shouldIncludeGroupIdInErrorMessage() {
            // Given
            mockCommittedOffsets(Map.of());

            // When
            String error = (String) check(check).get("error");

            // Then
            assertThat(error).contains(GROUP_ID);
        }

        @Test
        @DisplayName("status=group_not_found не содержит assignedPartitions и totalLag")
        void shouldNotContainLagFieldsWhenGroupNotFound() {
            // Given
            mockCommittedOffsets(Map.of());

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .doesNotContainKeys("assignedPartitions", "totalLag");
        }
    }

    @Nested
    @DisplayName("status=error — сбои AdminClient")
    class AdminClientFailures {

        @Test
        @DisplayName("status=error когда listConsumerGroupOffsets бросает ExecutionException")
        void shouldReportErrorWhenListOffsetsFails() throws Exception {
            // Given
            mockCommittedOffsetsFutureFails(new ExecutionException("Broker unavailable", new RuntimeException()));

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "ExecutionException")
                    .containsKey("error");
        }

        @Test
        @DisplayName("status=error когда listConsumerGroupOffsets бросает TimeoutException")
        void shouldReportErrorWhenListOffsetsTimesOut() throws Exception {
            // Given
            mockCommittedOffsetsFutureFails(new TimeoutException("Request timed out"));

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "TimeoutException");
        }

        @Test
        @DisplayName("status=error когда listOffsets (step 2) бросает ExecutionException")
        void shouldReportErrorWhenEndOffsetsFails() throws Exception {
            // Given
            mockCommittedOffsets(Map.of(PARTITION_0, committed(10L)));
            mockEndOffsetsFutureFails(new ExecutionException("Partition leader unavailable", new RuntimeException()));

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsEntry("status", "error")
                    .containsEntry("errorType", "ExecutionException");
        }
    }

    @Nested
    @DisplayName("details structure")
    class DetailsStructure {

        @Test
        @DisplayName("результат помещается под ключ 'consumer' в переданный map")
        void shouldPopulateConsumerKeyInGivenMap() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(0L)),
                    Map.of(PARTITION_0, endOffset(0L))
            );
            Map<String, Object> outer = new LinkedHashMap<>();

            // When
            check.check(adminClient, outer);

            // Then
            assertThat(outer).containsKey("consumer");
            assertThat(outer.get("consumer")).isInstanceOf(Map.class);
        }

        @Test
        @DisplayName("не затрагивает другие ключи в переданном map")
        void shouldNotModifyOtherKeysInGivenMap() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(0L)),
                    Map.of(PARTITION_0, endOffset(0L))
            );
            Map<String, Object> outer = new HashMap<>();
            outer.put("brokerCount", 3);
            outer.put("clusterId", "abc");

            // When
            check.check(adminClient, outer);

            // Then
            assertThat(outer)
                    .containsEntry("brokerCount", 3)
                    .containsEntry("clusterId", "abc");
        }

        @Test
        @DisplayName("reachable-details не содержит error/errorType")
        void shouldNotContainErrorKeysOnSuccess() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(5L)),
                    Map.of(PARTITION_0, endOffset(5L))
            );

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsKeys("status", "groupId", "assignedPartitions", "totalLag")
                    .doesNotContainKeys("error", "errorType");
        }

        @Test
        @DisplayName("error-details не содержит assignedPartitions/totalLag")
        void shouldNotContainLagKeysOnError() throws Exception {
            // Given
            mockCommittedOffsetsFutureFails(new ExecutionException("fail", new RuntimeException()));

            // When
            Map<String, Object> consumer = check(check);

            // Then
            assertThat(consumer)
                    .containsKeys("status", "groupId", "error", "errorType")
                    .doesNotContainKeys("assignedPartitions", "totalLag");
        }

        @Test
        @DisplayName("groupId идёт первым ключом в details (LinkedHashMap — порядок вставки)")
        void shouldReturnGroupIdAsFirstKey() {
            // Given
            mockOffsets(
                    Map.of(PARTITION_0, committed(0L)),
                    Map.of(PARTITION_0, endOffset(0L))
            );

            // When
            List<String> keys = new ArrayList<>(check(check).keySet());

            // Then
            assertThat(keys.getFirst()).isEqualTo("groupId");
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> check(KafkaConsumerHealthCheck check) {
        Map<String, Object> outer = new LinkedHashMap<>();
        check.check(adminClient, outer);
        return (Map<String, Object>) outer.get("consumer");
    }

    private void mockOffsets(Map<TopicPartition, OffsetAndMetadata> committed,
                             Map<TopicPartition, ListOffsetsResultInfo> endOffsets) {
        mockCommittedOffsets(committed);
        mockEndOffsets(endOffsets);
    }

    private void mockCommittedOffsets(Map<TopicPartition, OffsetAndMetadata> committed) {
        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future = completedFuture(committed);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(future);
        when(adminClient.listConsumerGroupOffsets(GROUP_ID)).thenReturn(offsetsResult);
    }

    private void mockEndOffsets(Map<TopicPartition, ListOffsetsResultInfo> endOffsets) {
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> future = completedFuture(endOffsets);
        when(listOffsetsResult.all()).thenReturn(future);
        when(adminClient.listOffsets(any())).thenReturn(listOffsetsResult);
    }

    private void mockCommittedOffsetsFutureFails(Exception cause) throws Exception {
        ListConsumerGroupOffsetsResult offsetsResult = mock(ListConsumerGroupOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, OffsetAndMetadata>> future = failedFuture(cause);
        when(offsetsResult.partitionsToOffsetAndMetadata()).thenReturn(future);
        when(adminClient.listConsumerGroupOffsets(GROUP_ID)).thenReturn(offsetsResult);
    }

    private void mockEndOffsetsFutureFails(Exception cause) throws Exception {
        ListOffsetsResult listOffsetsResult = mock(ListOffsetsResult.class);
        KafkaFuture<Map<TopicPartition, ListOffsetsResultInfo>> future = failedFuture(cause);
        when(listOffsetsResult.all()).thenReturn(future);
        when(adminClient.listOffsets(any())).thenReturn(listOffsetsResult);
    }

    private static OffsetAndMetadata committed(long offset) {
        return new OffsetAndMetadata(offset);
    }

    private static ListOffsetsResultInfo endOffset(long offset) {
        return new ListOffsetsResultInfo(offset, -1L, Optional.empty());
    }

    private static <T> KafkaFuture<T> completedFuture(T value) {
        return KafkaFuture.completedFuture(value);
    }

    @SuppressWarnings("unchecked")
    private static <T> KafkaFuture<T> failedFuture(Exception cause) throws Exception {
        KafkaFuture<T> future = mock(KafkaFuture.class);
        when(future.get(anyLong(), any())).thenThrow(cause);
        return future;
    }
}
