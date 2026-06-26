package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.extension.ResolvedRootCause;
import space.br1440.platform.errorhandling.extension.Retryability;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;
import space.br1440.platform.errorhandling.model.error_detail.RetryInfoDetail;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("RetryabilityResolver")
class RetryabilityResolverTest {

    @Nested
    @DisplayName("isRetryable(ErrorEntry, List<ResolvedRootCause>, HttpStatus)")
    class IsRetryableWithChain {

        @Test
        void emptyChain_nonRetryableStatus_returnsFalse() {
            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), List.of(), HttpStatus.INTERNAL_SERVER_ERROR))
                    .isFalse();
        }

        @Test
        void baseEntryWithoutRetryInfo_returnsFalse() {
            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), List.of(), HttpStatus.INTERNAL_SERVER_ERROR))
                    .isFalse();
        }

        @Test
        void baseEntryContainsRetryInfo_returnsTrue() {
            // when / then
            assertThat(RetryabilityResolver.isRetryable(
                    entryWithRetryInfo(), List.of(), HttpStatus.INTERNAL_SERVER_ERROR))
                    .isTrue();
        }

        @Test
        void chainWithRetryableCause_returnsTrue() {
            // given
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale", List.of());
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.CONFLICT))
                    .isTrue();
        }

        @Test
        void chainWithUnspecifiedCause_nonRetryableStatus_returnsFalse() {
            // given
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.CONFLICT, "duplicate", List.of());
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.CONFLICT))
                    .isFalse();
        }

        @Test
        void chainWithSecondCauseRetryable_returnsTrue() {
            // given
            RootCauseResolution first = RootCauseResolution.of(HttpStatus.CONFLICT, "dup", List.of());
            RootCauseResolution second = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale", List.of());
            var chain = List.of(resolved(first), resolved(second));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.CONFLICT))
                    .isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {408, 429, 502, 503, 504})
        void unspecifiedCause_retryableStatus_returnsTrue(int statusCode) {
            // given
            HttpStatus status = HttpStatus.valueOf(statusCode);
            RootCauseResolution cause = RootCauseResolution.of(status, "msg", List.of());
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, status))
                    .isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 403, 404, 409, 422, 500})
        void unspecifiedCause_nonRetryableStatus_returnsFalse(int statusCode) {
            // given
            HttpStatus status = HttpStatus.valueOf(statusCode);
            RootCauseResolution cause = RootCauseResolution.of(status, "msg", List.of());
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, status))
                    .isFalse();
        }

        @Test
        void notRetryableCause_blocksHttpStatusFallback() {
            // given
            RootCauseResolution cause = RootCauseResolution.notRetryable(
                    HttpStatus.SERVICE_UNAVAILABLE, "config error", List.of());
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.SERVICE_UNAVAILABLE))
                    .isFalse();
        }

        @Test
        void notRetryableBeforeRetryable_returnsFalse() {
            // given
            RootCauseResolution blocking = RootCauseResolution.notRetryable(HttpStatus.BAD_GATEWAY, "ssl", List.of());
            RootCauseResolution transient_ = RootCauseResolution.retryable(HttpStatus.SERVICE_UNAVAILABLE, "timeout", List.of());
            var chain = List.of(resolved(blocking), resolved(transient_));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.BAD_GATEWAY))
                    .isFalse();
        }

        @Test
        void retryableBeforeNotRetryable_returnsTrue() {
            // given
            RootCauseResolution transient_ = RootCauseResolution.retryable(HttpStatus.SERVICE_UNAVAILABLE, "timeout", List.of());
            RootCauseResolution blocking = RootCauseResolution.notRetryable(HttpStatus.BAD_GATEWAY, "ssl", List.of());
            var chain = List.of(resolved(transient_), resolved(blocking));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), chain, HttpStatus.BAD_GATEWAY))
                    .isTrue();
        }

        @Test
        void baseEntryRetryInfo_overridesNotRetryableCause() {
            // given
            RootCauseResolution blocking = RootCauseResolution.notRetryable(HttpStatus.BAD_GATEWAY, "ssl", List.of());
            var chain = List.of(resolved(blocking));

            // when / then
            assertThat(RetryabilityResolver.isRetryable(entryWithRetryInfo(), chain, HttpStatus.BAD_GATEWAY))
                    .isTrue();
        }
    }

    @Nested
    @DisplayName("isRetryable(ErrorEntry, RootCauseResolution)")
    class IsRetryableWithCauseDetails {

        @Test
        void baseEntryContainsRetryInfo_returnsTrue() {
            // given
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.CONFLICT, "msg", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(entryWithRetryInfo(), cause)).isTrue();
        }

        @Test
        void causeDetailsRetryable_returnsTrue() {
            // given
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), cause)).isTrue();
        }

        @Test
        void causeDetailsUnspecified_retryableStatus_returnsTrue() {
            // given
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.SERVICE_UNAVAILABLE, "unavailable", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), cause)).isTrue();
        }

        @Test
        void causeDetailsUnspecified_nonRetryableStatus_returnsFalse() {
            // given
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.NOT_FOUND, "not found", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), cause)).isFalse();
        }

        @Test
        void causeDetailsNotRetryable_blocksHttpStatusFallback() {
            // given
            RootCauseResolution cause = RootCauseResolution.notRetryable(
                    HttpStatus.SERVICE_UNAVAILABLE, "permanent failure", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(baseEntry(), cause)).isFalse();
        }

        @Test
        void baseEntryRetryInfo_overridesNotRetryableCause() {
            // given
            RootCauseResolution cause = RootCauseResolution.notRetryable(
                    HttpStatus.BAD_GATEWAY, "config error", List.of());

            // when / then
            assertThat(RetryabilityResolver.isRetryable(entryWithRetryInfo(), cause)).isTrue();
        }
    }

    @Nested
    @DisplayName("resolveRetryDelay(List<ResolvedRootCause>)")
    class ResolveRetryDelay {

        @Test
        void emptyChain_returnsNull() {
            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(List.of())).isNull();
        }

        @Test
        void chainWithDelayedRetryInfo_returnsFirstDelay() {
            // given
            Duration delay = Duration.ofSeconds(30);
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.TOO_MANY_REQUESTS, "rate limit",
                    List.of(new RetryInfoDetail(delay))
            );
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(chain)).isEqualTo(delay);
        }

        @Test
        void chainWithNullDelayRetryInfo_returnsNull() {
            // given
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale",
                    List.of(new RetryInfoDetail()));
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(chain)).isNull();
        }

        @Test
        void multipleCauses_returnsFirstNonNullDelay() {
            // given
            RootCauseResolution first = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale",
                    List.of(new RetryInfoDetail())
            );
            Duration secondDelay = Duration.ofSeconds(5);
            RootCauseResolution second = RootCauseResolution.retryable(HttpStatus.SERVICE_UNAVAILABLE, "unavailable",
                    List.of(new RetryInfoDetail(secondDelay))
            );
            var chain = List.of(resolved(first), resolved(second));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(chain)).isEqualTo(secondDelay);
        }
    }

    @Nested
    @DisplayName("resolveRetryDelay(ErrorEntry, List<ResolvedRootCause>)")
    class ResolveRetryDelayWithBaseEntry {

        @Test
        void baseEntryDelayTakesPriority() {
            // given
            Duration baseDelay = Duration.ofSeconds(60);
            ErrorEntry base = ErrorEntry.builder()
                    .httpStatus(HttpStatus.TOO_MANY_REQUESTS)
                    .errorCode("RATE-001")
                    .message("rate limited")
                    .details(List.of(new RetryInfoDetail(baseDelay)))
                    .build();
            Duration chainDelay = Duration.ofSeconds(5);
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.TOO_MANY_REQUESTS, "limit",
                    List.of(new RetryInfoDetail(chainDelay))
            );
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(base, chain)).isEqualTo(baseDelay);
        }

        @Test
        void baseEntryNoDelay_fallsBackToChain() {
            // given
            Duration chainDelay = Duration.ofSeconds(10);
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.TOO_MANY_REQUESTS, "limit",
                    List.of(new RetryInfoDetail(chainDelay))
            );
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(baseEntry(), chain)).isEqualTo(chainDelay);
        }

        @Test
        void baseEntryNullDelay_fallsBackToChain() {
            // given
            ErrorEntry base = ErrorEntry.builder()
                    .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                    .errorCode("SVC-001")
                    .message("unavailable")
                    .details(List.of(new RetryInfoDetail()))
                    .build();
            Duration chainDelay = Duration.ofSeconds(15);
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.SERVICE_UNAVAILABLE, "msg",
                    List.of(new RetryInfoDetail(chainDelay))
            );
            var chain = List.of(resolved(cause));

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(base, chain)).isEqualTo(chainDelay);
        }

        @Test
        void emptyChain_noBaseDelay_returnsNull() {
            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(baseEntry(), List.of())).isNull();
        }
    }

    @Nested
    @DisplayName("resolveRetryDelay(RootCauseResolution)")
    class ResolveRetryDelayFromCauseDetails {

        @Test
        void causeDetailsWithDelay_returnsDelay() {
            // given
            Duration delay = Duration.ofSeconds(20);
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.TOO_MANY_REQUESTS, "limit",
                    List.of(new RetryInfoDetail(delay))
            );

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(cause)).isEqualTo(delay);
        }

        @Test
        void causeDetailsWithoutDelay_returnsNull() {
            // given
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.CONFLICT, "stale",
                    List.of(new RetryInfoDetail())
            );

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(cause)).isNull();
        }

        @Test
        void causeDetailsNoRetryInfo_returnsNull() {
            // given
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.CONFLICT, "msg", List.of());

            // when / then
            assertThat(RetryabilityResolver.resolveRetryDelay(cause)).isNull();
        }
    }

    @Nested
    @DisplayName("containsRetryInfo")
    class ContainsRetryInfo {

        @Test
        void nullDetails_returnsFalse() {
            // when / then
            assertThat(RetryabilityResolver.containsRetryInfo(absentDetails())).isFalse();
        }

        private List<ErrorDetail> absentDetails() {
            return null;
        }

        @Test
        void emptyDetails_returnsFalse() {
            // when / then
            assertThat(RetryabilityResolver.containsRetryInfo(List.of())).isFalse();
        }

        @Test
        void detailsWithRetryInfo_returnsTrue() {
            // when / then
            assertThat(RetryabilityResolver.containsRetryInfo(List.of(new RetryInfoDetail()))).isTrue();
        }

        @Test
        void detailsWithoutRetryInfo_returnsFalse() {
            // when / then
            assertThat(RetryabilityResolver.containsRetryInfo(List.of(ErrorInfoDetail.builder().build()))).isFalse();
        }
    }

    @Nested
    @DisplayName("isRetryableByStatus")
    class IsRetryableByStatus {

        @ParameterizedTest
        @ValueSource(ints = {408, 429, 502, 503, 504})
        void retryableStatuses_returnTrue(int statusCode) {
            // when / then
            assertThat(RetryabilityResolver.isRetryableByStatus(HttpStatus.valueOf(statusCode))).isTrue();
        }

        @ParameterizedTest
        @ValueSource(ints = {400, 401, 403, 404, 409, 422, 500})
        void nonRetryableStatuses_returnFalse(int statusCode) {
            // when / then
            assertThat(RetryabilityResolver.isRetryableByStatus(HttpStatus.valueOf(statusCode))).isFalse();
        }
    }

    @Nested
    @DisplayName("Фабричные методы RootCauseResolution → Retryability")
    class RetryabilityFactoryMethods {

        @Test
        void threeArgFactory_mapsToUnspecified() {
            // when
            RootCauseResolution cause = RootCauseResolution.of(HttpStatus.CONFLICT, "msg", List.of());

            // then
            assertThat(cause.retryability()).isEqualTo(Retryability.UNSPECIFIED);
        }

        @Test
        void fourArgFactory_preservesRetryability() {
            // when
            RootCauseResolution cause = RootCauseResolution.of(
                    HttpStatus.CONFLICT, "msg", List.of(), Retryability.RETRYABLE
            );

            // then
            assertThat(cause.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void retryableFactory_mapsToRetryable() {
            // when
            RootCauseResolution cause = RootCauseResolution.retryable(HttpStatus.CONFLICT, "msg", List.of());

            // then
            assertThat(cause.retryability()).isEqualTo(Retryability.RETRYABLE);
        }

        @Test
        void notRetryableFactory_mapsToNotRetryable() {
            // when
            RootCauseResolution cause = RootCauseResolution.notRetryable(HttpStatus.CONFLICT, "msg", List.of());

            // then
            assertThat(cause.retryability()).isEqualTo(Retryability.NOT_RETRYABLE);
        }
    }

    private static ErrorEntry baseEntry() {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.INTERNAL_SERVER_ERROR)
                .errorCode("TEST-001")
                .message("test error")
                .build();
    }

    private static ErrorEntry entryWithRetryInfo() {
        return ErrorEntry.builder()
                .httpStatus(HttpStatus.SERVICE_UNAVAILABLE)
                .errorCode("TEST-002")
                .message("retryable error")
                .details(List.of(new RetryInfoDetail()))
                .build();
    }

    private static ResolvedRootCause resolved(RootCauseResolution resolution) {
        return new ResolvedRootCause(new RuntimeException("test cause"), resolution);
    }

}
