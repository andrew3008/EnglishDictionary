package space.br1440.platform.errorhandling.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.RootCauseExceptionHandlingStrategy;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.testing.ExceptionChainBuilder;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;
import static space.br1440.platform.errorhandling.testing.assertions.ErrorHandlingAssertions.assertThat;

/**
 * Интеграционный тест полного pipeline обработки ошибок <b>без Spring-контекста</b>.
 *
 * <p>Проверяемый путь: Exception → CauseChainResolver → RootCauseResolution → ErrorEntry.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("Pipeline: Exception → CauseChainResolver → ErrorEntry")
class StrategyPipelineIntegrationTest {

    @Mock
    private RootCauseExceptionHandlingStrategy strategy;

    // ------------------------------------------------------------------ //
    //  Сценарий 1: единичное исключение без цепочки причин
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("Единичное исключение без cause chain")
    class SingleExceptionTests {

        @Test
        @DisplayName("resolver находит подходящую стратегию и возвращает корректный resolution")
        void resolverFindsMatchingStrategyAndReturnsResolution() {
            // given
            ErrorInfoDetail infoDetail = ErrorInfoDetail.builder()
                    .reason("INVALID_ARGUMENT")
                    .build();

            RootCauseResolution expectedResolution = RootCauseResolution.of(
                    HttpStatus.BAD_REQUEST,
                    "Invalid argument provided",
                    List.of(infoDetail)
            );

            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(true);
            when(strategy.extractCauseDetails(any())).thenReturn(expectedResolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            IllegalArgumentException exception = new IllegalArgumentException("bad param");

            // when
            ResolvedRootCause resolved = resolver.resolve(exception);

            // then
            assertThat(resolved.cause()).isSameAs(exception);
            assertThat(resolved.resolution())
                    .hasHttpStatus(HttpStatus.BAD_REQUEST)
                    .hasMessage("Invalid argument provided")
                    .hasDetailsCount(1);
        }
    }

    // ------------------------------------------------------------------ //
    //  Сценарий 2: цепочка из трёх уровней
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("Цепочка причин (3 уровня)")
    class CauseChainTests {

        @Test
        @DisplayName("resolver обходит цепочку и находит TimeoutException на третьем уровне")
        void resolverTraversesCauseChainAndMatchesDeepestCause() {
            // given
            Throwable chain = ExceptionChainBuilder
                    .root(TimeoutException.class, "connection timed out")
                    .wrappedBy(IllegalStateException.class, "state corrupted")
                    .wrappedBy(RuntimeException.class, "top-level failure")
                    .build();

            ErrorInfoDetail timeoutInfo = ErrorInfoDetail.builder()
                    .reason("TIMEOUT")
                    .diagnosticMetadata(Map.of(
                            "rootCauseClass", TimeoutException.class.getName(),
                            "rootCauseMessage", "connection timed out"
                    ))
                    .build();

            RootCauseResolution timeoutResolution = RootCauseResolution.of(
                    HttpStatus.GATEWAY_TIMEOUT,
                    "Upstream service timed out",
                    List.of(timeoutInfo)
            );

            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenAnswer(inv -> {
                Throwable t = inv.getArgument(0);
                return t instanceof TimeoutException;
            });
            when(strategy.extractCauseDetails(any())).thenReturn(timeoutResolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when
            ResolvedRootCause resolved = resolver.resolve(chain);

            // then
            assertThat(resolved.cause()).isInstanceOf(TimeoutException.class);
            assertThat(resolved.resolution())
                    .hasHttpStatus(HttpStatus.GATEWAY_TIMEOUT)
                    .hasMessage("Upstream service timed out");
        }
    }

    // ------------------------------------------------------------------ //
    //  Сценарий 3: построение ErrorEntry из данных resolution
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("Построение ErrorEntry из RootCauseResolution")
    class ErrorEntryConstructionTests {

        @Test
        @DisplayName("ErrorEntry корректно собирается из resolution — httpStatus, errorCode, message, details")
        void errorEntryIsBuiltFromResolutionData() {
            // given
            ErrorInfoDetail detail = ErrorInfoDetail.builder()
                    .reason("SERVICE_UNAVAILABLE")
                    .clientMetadata(Map.of("serviceLayer", "downstream"))
                    .build();

            RootCauseResolution resolution = RootCauseResolution.of(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Downstream service is unavailable",
                    List.of(detail)
            );

            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(true);
            when(strategy.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            ResolvedRootCause resolved = resolver.resolve(new RuntimeException("fail"));

            RootCauseResolution res = resolved.resolution();
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(res.httpStatus())
                    .errorCode("SVC-0503")
                    .message(res.message())
                    .details(res.details())
                    .build();

            // when / then
            assertThat(entry)
                    .hasHttpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .hasErrorCode("SVC-0503")
                    .hasMessage("Downstream service is unavailable")
                    .hasDetailsCount(1)
                    .hasDetailsOfType(ErrorInfoDetail.class);
        }
    }

    // ------------------------------------------------------------------ //
    //  Сценарий 4: dual-metadata — clientMetadata + diagnosticMetadata
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("Dual-metadata: clientMetadata и diagnosticMetadata")
    class DualMetadataTests {

        @Test
        @DisplayName("оба набора метаданных сохраняются в ErrorEntry после прохождения pipeline")
        void bothMetadataSetsArePreservedInErrorEntry() {
            // given
            ErrorInfoDetail infoDetail = ErrorInfoDetail.builder()
                    .reason("DB_CONNECTION_FAILURE")
                    .clientMetadata(Map.of(
                            "errorType", "ConnectionFailure",
                            "layer", "persistence"
                    ))
                    .diagnosticMetadata(Map.of(
                            "rootCauseClass", "org.postgresql.util.PSQLException",
                            "rootCauseMessage", "Connection refused",
                            "remoteHost", "db-primary.internal"
                    ))
                    .build();

            RootCauseResolution resolution = RootCauseResolution.of(
                    HttpStatus.SERVICE_UNAVAILABLE,
                    "Database connection failed",
                    List.of(infoDetail)
            );

            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(true);
            when(strategy.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));
            ResolvedRootCause resolved = resolver.resolve(new RuntimeException("db error"));

            RootCauseResolution res = resolved.resolution();
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(res.httpStatus())
                    .errorCode("DB-0001")
                    .message(res.message())
                    .details(res.details())
                    .build();

            // when / then
            assertThat(entry)
                    .hasHttpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .extractingFirstErrorInfo()
                    .hasReason("DB_CONNECTION_FAILURE")
                    .clientMetadataContains("errorType", "ConnectionFailure")
                    .clientMetadataContains("layer", "persistence")
                    .diagnosticMetadataContains("rootCauseClass", "org.postgresql.util.PSQLException")
                    .diagnosticMetadataContains("rootCauseMessage", "Connection refused")
                    .diagnosticMetadataContains("remoteHost", "db-primary.internal")
                    .diagnosticMetadataIsNotExposedToClient();
        }
    }

    // ------------------------------------------------------------------ //
    //  Сценарий 5: retryable-семантика
    // ------------------------------------------------------------------ //

    @Nested
    @DisplayName("Retryable-семантика")
    class RetryableTests {

        @Test
        @DisplayName("ErrorEntry с RetryInfoDetail распознаётся как retryable")
        void errorEntryWithRetryInfoDetailIsRetryable() {
            // given
            RetryInfoDetail retryInfo = new RetryInfoDetail(Duration.ofSeconds(5));
            ErrorInfoDetail infoDetail = ErrorInfoDetail.builder()
                    .reason("RATE_LIMITED")
                    .build();

            List<ErrorDetail> details = List.of(infoDetail, retryInfo);

            RootCauseResolution resolution = RootCauseResolution.of(
                    HttpStatus.TOO_MANY_REQUESTS,
                    "Rate limit exceeded",
                    details,
                    Retryability.RETRYABLE
            );

            lenient().when(strategy.getOrder()).thenReturn(0);
            when(strategy.supportsCause(any())).thenReturn(true);
            when(strategy.extractCauseDetails(any())).thenReturn(resolution);

            CauseChainResolver resolver = new CauseChainResolver(List.of(strategy));

            // when
            ResolvedRootCause resolved = resolver.resolve(new RuntimeException("rate limited"));

            // then
            assertThat(resolved.resolution())
                    .hasHttpStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .hasRetryability(Retryability.RETRYABLE)
                    .containsRetryInfo();

            RootCauseResolution res = resolved.resolution();
            ErrorEntry entry = ErrorEntry.builder()
                    .httpStatus(res.httpStatus())
                    .errorCode("SVC-0429")
                    .message(res.message())
                    .details(res.details())
                    .build();

            assertThat(entry)
                    .hasHttpStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .isRetryable()
                    .hasRetryDelay(Duration.ofSeconds(5))
                    .hasDetailsOfType(RetryInfoDetail.class)
                    .hasDetailsOfType(ErrorInfoDetail.class);
        }
    }

}
