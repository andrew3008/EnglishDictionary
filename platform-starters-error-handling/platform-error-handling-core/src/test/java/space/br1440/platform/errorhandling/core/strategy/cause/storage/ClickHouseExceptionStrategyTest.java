package space.br1440.platform.errorhandling.core.strategy.cause.storage;

import com.clickhouse.client.api.ClickHouseException;
import com.clickhouse.client.api.ClientException;
import com.clickhouse.client.api.ClientMisconfigurationException;
import com.clickhouse.client.api.ConnectionInitiationException;
import com.clickhouse.client.api.DataTransferException;
import com.clickhouse.client.api.ServerException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("ClickHouseExceptionStrategy")
class ClickHouseExceptionStrategyTest {

    private static final int HTTP_500 = 500;

    /**
     * Стратегия с дефолтным includeServerInfo=false и без stackHint
     * для большинства тестов — снижает шум в проверках diagnosticMetadata.
     */
    private final ClickHouseExceptionStrategy strategy =
            new ClickHouseExceptionStrategy(new StackHintExtractor(false, List.of()));

    /**
     * Стратегия с включённым stackHint — для тестов diagnosticMetadata,
     * требующих наличия поля {@code stackHint}.
     */
    private final ClickHouseExceptionStrategy strategyWithHints =
            new ClickHouseExceptionStrategy(new StackHintExtractor(true, List.of()));

    /**
     * Хелпер для конструирования {@code ServerException} с серверным кодом
     * и дефолтным транспортным HTTP-статусом 500.
     */
    private static ServerException serverEx(int code, String message) {
        return new ServerException(code, message, HTTP_500, "test-query-id");
    }

    private static ServerException serverEx(int code, String message, int transportStatus) {
        return new ServerException(code, message, transportStatus, "test-query-id");
    }

    private static ErrorInfoDetail findInfoDetail(RootCauseResolution details) {
        return details.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ErrorInfoDetail not found"));
    }

    // ============================================================================
    // supportsCause
    // ============================================================================

    @Nested
    @DisplayName("supportsCause")
    class SupportsCause {

        @Test
        void supports_ClickHouseException_base() {
            // when / then
            assertThat(strategy.supportsCause(new ClickHouseException("base error"))).isTrue();
        }

        @Test
        void supports_ServerException() {
            // when / then
            assertThat(strategy.supportsCause(serverEx(159, "timeout"))).isTrue();
        }

        @Test
        void supports_ClientException() {
            // when / then
            assertThat(strategy.supportsCause(new ClientException("client error"))).isTrue();
        }

        @Test
        void supports_ClientMisconfigurationException() {
            // when / then
            assertThat(strategy.supportsCause(new ClientMisconfigurationException("misconfigured"))).isTrue();
        }

        @Test
        void supports_ConnectionInitiationException() {
            // when / then
            assertThat(strategy.supportsCause(new ConnectionInitiationException("handshake failed"))).isTrue();
        }

        @Test
        void supports_DataTransferException() {
            // when / then
            assertThat(strategy.supportsCause(new DataTransferException("transfer error"))).isTrue();
        }

        @Test
        void does_not_support_RuntimeException() {
            // when / then
            assertThat(strategy.supportsCause(new RuntimeException("unrelated"))).isFalse();
        }

        @Test
        void does_not_support_SQLException() {
            // when / then
            assertThat(strategy.supportsCause(new SQLException("sql", "22000", 159))).isFalse();
        }

        @Test
        void does_not_support_IllegalStateException() {
            // when / then
            assertThat(strategy.supportsCause(new IllegalStateException("illegal"))).isFalse();
        }
    }

    // ============================================================================
    // Authentication / Authorization
    // ============================================================================

    @Nested
    @DisplayName("Authentication & Access errors")
    class AuthenticationAndAccess {

        @Test
        void code_192_unknown_user_returns_401_AUTH_FAILED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(192, "user 'admin' not found"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("AUTH_FAILED");
        }

        @Test
        void code_193_wrong_password_returns_401_AUTH_FAILED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(193, "wrong password"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("AUTH_FAILED");
        }

        @Test
        void code_516_authentication_failed_returns_401() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(516, "auth failed"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNAUTHORIZED);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("AUTH_FAILED");
        }

        @Test
        void code_195_ip_not_allowed_returns_403_ACCESS_DENIED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(195, "ip not allowed"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void code_291_database_access_denied_returns_403() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(291, "db access denied"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void code_497_access_denied_returns_403() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(497, "permission denied"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.FORBIDDEN);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void auth_errors_are_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(193, "wrong password"));

            // then
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Read-only / replica
    // ============================================================================

    @Nested
    @DisplayName("Read-only errors (retryable per official driver)")
    class ReadOnly {

        @Test
        void code_164_readonly_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(164, "readonly mode"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("READ_ONLY");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_242_table_read_only_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(242, "table is read-only"));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("READ_ONLY");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Not found
    // ============================================================================

    @Nested
    @DisplayName("Resource not found")
    class NotFound {

        @Test
        void code_60_unknown_table_returns_404() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(60, "Table 'foo' doesn't exist"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NOT_FOUND");
        }

        @Test
        void code_81_unknown_database_returns_404() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(81, "Database 'bar' doesn't exist"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.NOT_FOUND);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NOT_FOUND");
        }
    }

    // ============================================================================
    // Bad query
    // ============================================================================

    @Nested
    @DisplayName("Bad query / programming errors")
    class BadQuery {

        @Test
        void code_36_bad_arguments_returns_400() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(36, "bad arguments"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void code_47_unknown_identifier_returns_400() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(47, "unknown column"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void code_53_type_mismatch_returns_400() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(53, "type mismatch"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void code_62_syntax_error_returns_400() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(62, "syntax error"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void code_115_unknown_setting_returns_400() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(115, "unknown setting"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void bad_query_is_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(62, "syntax error"));

            // then
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Bad data
    // ============================================================================

    @Nested
    @DisplayName("Bad data / parsing errors")
    class BadData {

        @Test
        void code_6_cannot_parse_text_returns_422() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(6, "cannot parse text"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_DATA");
        }

        @Test
        void code_38_cannot_parse_date_returns_422() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(38, "cannot parse date"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        }

        @Test
        void code_117_incorrect_data_returns_422() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(117, "incorrect data"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_DATA");
        }

        @Test
        void code_469_violated_constraint_returns_422() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(469, "constraint violated"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_DATA");
        }
    }

    // ============================================================================
    // Conflict
    // ============================================================================

    @Nested
    @DisplayName("Conflict / already exists")
    class Conflict {

        @Test
        void code_57_table_already_exists_returns_409() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(57, "table already exists"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("CONFLICT");
        }

        @Test
        void code_82_database_already_exists_returns_409() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(82, "database already exists"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
        }

        @Test
        void code_216_query_with_same_id_running_returns_409() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(216, "query with same id is already running"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("CONFLICT");
        }

        @Test
        void code_473_deadlock_avoided_returns_409_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(473, "deadlock avoided"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.CONFLICT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("DEADLOCK_AVOIDED");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void plain_conflicts_are_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(57, "table already exists"));

            // then
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Quotas
    // ============================================================================

    @Nested
    @DisplayName("Quotas / rate limits")
    class Quotas {

        @Test
        void code_201_quota_exceeded_returns_429_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(201, "quota exceeded"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("QUOTA_EXCEEDED");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_290_limit_exceeded_returns_429() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(290, "limit exceeded"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        void code_364_too_many_requests_returns_429_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(364, "too many requests"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Payload too large
    // ============================================================================

    @Nested
    @DisplayName("Payload size limits")
    class PayloadTooLarge {

        @Test
        void code_158_too_many_rows_returns_413() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(158, "too many rows"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("PAYLOAD_TOO_LARGE");
        }

        @Test
        void code_396_too_many_rows_or_bytes_returns_413() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(396, "limit exceeded"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.PAYLOAD_TOO_LARGE);
        }
    }

    // ============================================================================
    // Timeouts
    // ============================================================================

    @Nested
    @DisplayName("Timeouts (504, retryable)")
    class Timeouts {

        @Test
        void code_159_timeout_exceeded_returns_504_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(159, "Timeout exceeded: elapsed 30s"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("TIMEOUT");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_209_socket_timeout_returns_504_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(209, "socket timeout"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_394_query_cancelled_returns_504_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(394, "query was cancelled"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.GATEWAY_TIMEOUT);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("TIMEOUT");
        }
    }

    // ============================================================================
    // Network
    // ============================================================================

    @Nested
    @DisplayName("Network errors (503, retryable)")
    class Network {

        @Test
        void code_198_dns_error_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(198, "dns failure"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NETWORK");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_210_network_error_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(210, "network error"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_279_all_connection_tries_failed_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(279, "all connection tries failed"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NETWORK");
        }
    }

    // ============================================================================
    // Resource exhaustion
    // ============================================================================

    @Nested
    @DisplayName("Resource exhaustion")
    class ResourceExhaustion {

        @Test
        void code_173_cannot_allocate_memory_is_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(173, "cannot allocate memory"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("MEMORY_NON_RETRYABLE");
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_241_memory_limit_exceeded_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(241, "memory limit exceeded"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("MEMORY_RETRYABLE");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_243_not_enough_space_returns_507() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(243, "not enough disk space"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INSUFFICIENT_STORAGE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("STORAGE_FULL");
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Overloaded
    // ============================================================================

    @Nested
    @DisplayName("Server overloaded (503, retryable)")
    class Overloaded {

        @Test
        void code_202_too_many_simultaneous_queries_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(202, "too many simultaneous queries"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("OVERLOADED");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_252_too_many_parts_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(252, "too many parts"));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("OVERLOADED");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_565_too_many_partitions_is_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(565, "too many partitions"));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("OVERLOADED");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Replication
    // ============================================================================

    @Nested
    @DisplayName("Replication / coordinator errors")
    class Replication {

        @Test
        void code_254_no_active_replicas_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(254, "no active replicas"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("REPLICATION");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_285_too_few_live_replicas_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(285, "too few live replicas"));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("REPLICATION");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_999_keeper_exception_returns_503_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(999, "keeper error"));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("REPLICATION");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }
    }

    // ============================================================================
    // Insert status unknown
    // ============================================================================

    @Nested
    @DisplayName("Insert status unknown (302, retryable iff idempotent)")
    class InsertStatusUnknown {

        @Test
        void code_319_returns_502_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(319, "unknown status of insert"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("INSERT_STATUS_UNKNOWN");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_319_message_warns_about_idempotency() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(319, "unknown insert status"));

            // then — само сообщение в resolution предупреждает о требовании идемпотентности
            assertThat(res.message()).containsIgnoringCase("idempotent");
        }
    }

    // ============================================================================
    // Server internal errors
    // ============================================================================

    @Nested
    @DisplayName("Server internal errors")
    class ServerInternal {

        @Test
        void code_49_logical_error_returns_500_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(49, "logical error"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("LOGICAL_ERROR");
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_425_system_error_returns_500_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(425, "system error"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("SYSTEM_ERROR");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void code_555_rocksdb_error_returns_500_INTERNAL() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(555, "rocksdb failed"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("INTERNAL");
        }
    }

    // ============================================================================
    // Client subtypes
    // ============================================================================

    @Nested
    @DisplayName("Client-side exception subtypes")
    class ClientSubtypes {

        @Test
        void ClientMisconfigurationException_returns_500_CLIENT_MISCONFIG() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ClientMisconfigurationException("invalid endpoint url"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("CLIENT_MISCONFIG");
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void ConnectionInitiationException_returns_503_CONNECTION_INIT_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ConnectionInitiationException("ssl handshake failed"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("CONNECTION_INIT");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void DataTransferException_returns_502_DATA_TRANSFER_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(
                    new DataTransferException("connection reset"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("DATA_TRANSFER");
            assertThat(res.details()).anyMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void plain_ClientException_returns_502_CLIENT_not_retryable() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ClientException("client failure"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("CLIENT");
            assertThat(res.details()).noneMatch(d -> d instanceof RetryInfoDetail);
        }

        @Test
        void plain_ClickHouseException_returns_500_GENERIC() {
            // when — bare ClickHouseException без конкретного подтипа
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ClickHouseException("unknown error"));

            // then
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
            assertThat(findInfoDetail(res).getReason()).isEqualTo("GENERIC");
        }
    }

    // ============================================================================
    // Transport HTTP status fallback
    // ============================================================================

    @Nested
    @DisplayName("Transport HTTP status fallback (when server code is unknown)")
    class TransportFallback {

        /**
         * Код 99999 — намеренно несуществующий, чтобы попасть в fallback по HTTP-статусу.
         */
        @Test
        void unknown_code_with_500_transport_returns_SERVER_ERROR() {
            // given
            ServerException ex = serverEx(99_999, "unmapped code", 500);

            // when
            RootCauseResolution res = strategy.extractCauseDetails(ex);

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("SERVER_ERROR");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.BAD_GATEWAY);
        }

        @Test
        void unknown_code_with_503_transport_returns_SERVER_ERROR() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "msg", 503));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("SERVER_ERROR");
        }

        @Test
        void unknown_code_with_429_transport_returns_QUOTA_EXCEEDED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "throttled", 429));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("QUOTA_EXCEEDED");
            assertThat(res.httpStatus()).isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
        }

        @Test
        void unknown_code_with_413_transport_returns_PAYLOAD_TOO_LARGE() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "too large", 413));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("PAYLOAD_TOO_LARGE");
        }

        @Test
        void unknown_code_with_404_transport_returns_NOT_FOUND() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "not found", 404));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("NOT_FOUND");
        }

        @Test
        void unknown_code_with_403_transport_returns_ACCESS_DENIED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "forbidden", 403));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void unknown_code_with_401_transport_returns_ACCESS_DENIED() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "unauthorized", 401));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("ACCESS_DENIED");
        }

        @Test
        void unknown_code_with_400_transport_returns_BAD_QUERY() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(99_999, "bad request", 400));

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("BAD_QUERY");
        }

        @Test
        void zero_code_falls_back_to_transport_status() {
            // given — code=0 ⇒ ServerException.CODE_UNKNOWN, попадает в fallback
            ServerException ex = serverEx(0, "Code: 0. Unknown server error", 500);

            // when
            RootCauseResolution res = strategy.extractCauseDetails(ex);

            // then
            assertThat(findInfoDetail(res).getReason()).isEqualTo("SERVER_ERROR");
        }
    }

    // ============================================================================
    // Diagnostic metadata
    // ============================================================================

    @Nested
    @DisplayName("Diagnostic metadata")
    class DiagnosticMetadata {

        @Test
        void contains_exceptionClass() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("exceptionClass", "com.clickhouse.client.api.ServerException");
        }

        @Test
        void contains_truncated_causeMessage() {
            // given
            String shortMessage = "timeout exceeded after 30s";

            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, shortMessage));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag.get("causeMessage")).contains(shortMessage);
        }

        @Test
        void truncates_long_causeMessage_to_300_chars() {
            // given
            String longMessage = "x".repeat(500);

            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, longMessage));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then — точная длина: 300 символов сообщения + маркер усечения
            assertThat(diag.get("causeMessage")).startsWith("x".repeat(300));
            assertThat(diag.get("causeMessage")).contains("[TRUNCATED]");
        }

        @Test
        void contains_chErrorCode_for_ServerException() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chErrorCode", "159");
        }

        @Test
        void contains_chErrorCodeName_for_known_code() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chErrorCodeName", "TIMEOUT_EXCEEDED");
        }

        @Test
        void contains_chErrorCodeName_for_60_unknown_table() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(60, "table not found"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chErrorCodeName", "UNKNOWN_TABLE");
        }

        @Test
        void omits_chErrorCodeName_for_unmapped_code() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(99_999, "weird"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("chErrorCodeName");
        }

        @Test
        void contains_chTransportStatus() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout", 504));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chTransportStatus", "504");
        }

        @Test
        void contains_chQueryId() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chQueryId", "test-query-id");
        }

        @Test
        void omits_chQueryId_when_blank() {
            // given
            ServerException ex = new ServerException(159, "timeout", 504, "");

            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(ex);
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("chQueryId");
        }

        @Test
        void contains_chServerRetryableHint_for_retryable_code() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then — драйвер сам помечает 159 как retryable
            assertThat(diag).containsEntry("chServerRetryableHint", "true");
        }

        @Test
        void contains_chServerRetryableHint_false_for_non_retryable_code() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(60, "no table"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsEntry("chServerRetryableHint", "false");
        }

        @Test
        void omits_server_specific_metadata_for_non_server_exception() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(
                    new ConnectionInitiationException("handshake failed"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).doesNotContainKey("chErrorCode");
            assertThat(diag).doesNotContainKey("chTransportStatus");
            assertThat(diag).doesNotContainKey("chServerRetryableHint");
        }

        @Test
        void contains_stackHint_when_extractor_enabled() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThat(diag).containsKey("stackHint");
        }

        @Test
        void diagnosticMetadata_is_unmodifiable() {
            // when
            RootCauseResolution res = strategyWithHints.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then
            assertThatThrownBy(() -> diag.put("k", "v"))
                    .isInstanceOf(UnsupportedOperationException.class);
        }

        @Test
        void chServer_is_absent_when_includeServerInfo_is_false() {
            // when
            RootCauseResolution res = strategy.extractCauseDetails(serverEx(159, "timeout"));
            Map<String, String> diag = findInfoDetail(res).getDiagnosticMetadata();

            // then — поле зарезервировано на будущее, но в текущем драйвере отсутствует
            assertThat(diag).doesNotContainKey("chServer");
        }
    }

    // ============================================================================
    // Domain
    // ============================================================================

    @Nested
    @DisplayName("Domain")
    class Domain {

        @Test
        void domain_is_clickhouse_for_ServerException() {
            // when / then
            assertThat(findInfoDetail(strategy.extractCauseDetails(serverEx(159, "timeout"))).getDomain())
                    .isEqualTo("clickhouse");
        }

        @Test
        void domain_is_clickhouse_for_ClientException() {
            // when / then
            assertThat(findInfoDetail(strategy.extractCauseDetails(new ClientException("err"))).getDomain())
                    .isEqualTo("clickhouse");
        }

        @Test
        void domain_is_clickhouse_for_ConnectionInitiationException() {
            // when / then
            assertThat(findInfoDetail(strategy.extractCauseDetails(new ConnectionInitiationException("err"))).getDomain())
                    .isEqualTo("clickhouse");
        }

        @Test
        void domain_is_clickhouse_for_base_ClickHouseException() {
            // when / then
            assertThat(findInfoDetail(strategy.extractCauseDetails(new ClickHouseException("err"))).getDomain())
                    .isEqualTo("clickhouse");
        }
    }

    // ============================================================================
    // Order
    // ============================================================================

    @Nested
    @DisplayName("Order")
    class Order {

        @Test
        void order_is_138() {
            // when / then
            assertThat(strategy.getOrder()).isEqualTo(138);
        }

        @Test
        void order_is_after_postgres_and_before_hibernate() {
            // given
            int postgresOrder = 135;
            int hibernateOrder = 140;

            // when
            int clickhouseOrder = strategy.getOrder();

            // then
            assertThat(clickhouseOrder).isGreaterThan(postgresOrder);
            assertThat(clickhouseOrder).isLessThan(hibernateOrder);
        }

        @Test
        void order_is_before_data_access() {
            // given
            int dataAccessOrder = 150;

            // when
            int clickhouseOrder = strategy.getOrder();

            // then — критично для делегирования из UncategorizedSQLException
            assertThat(clickhouseOrder).isLessThan(dataAccessOrder);
        }
    }
}
