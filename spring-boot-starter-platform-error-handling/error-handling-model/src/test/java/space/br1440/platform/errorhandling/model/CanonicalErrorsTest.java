package space.br1440.platform.errorhandling.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.provider.Arguments;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.utils.CanonicalErrors;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CanonicalErrorCodes")
class CanonicalErrorsTest {

//    @Nested
//    @DisplayName("resolveErrorCode")
//    class FromHttpStatus {
//
//        @ParameterizedTest(name = "{0} -> {1}")
//        @MethodSource("space.br1440.platform.errorhandling.utils.CanonicalErrorCodesTest#explicitMappings")
//        @DisplayName("explicit switch branches map to expected error codes")
//        void explicitBranch(HttpStatus status, String expectedCode) {
//            assertThat(CanonicalErrorCodes.resolveErrorCode(status)).isEqualTo(expectedCode);
//        }
//
//        @Test
//        @DisplayName("unmapped 4xx defaults to INVALID_ARGUMENT")
//        void unmapped4xx_defaultsToInvalidArgument() {
//            assertThat(CanonicalErrorCodes.resolveErrorCode(HttpStatus.METHOD_NOT_ALLOWED))
//                    .isEqualTo(CanonicalErrorCodes.INVALID_ARGUMENT);
//        }
//
//        @Test
//        @DisplayName("unmapped 5xx defaults to INTERNAL")
//        void unmapped5xx_defaultsToInternal() {
//            assertThat(CanonicalErrorCodes.resolveErrorCode(HttpStatus.HTTP_VERSION_NOT_SUPPORTED))
//                    .isEqualTo(CanonicalErrorCodes.INTERNAL);
//        }
//
//        @Test
//        @DisplayName("null status throws NullPointerException")
//        void nullStatus_throwsNpe() {
//            assertThatNullPointerException()
//                    .isThrownBy(() -> CanonicalErrorCodes.resolveErrorCode(null))
//                    .withMessage("status must not be null");
//        }
//    }

    static Stream<Arguments> explicitMappings() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, CanonicalErrors.INVALID_ARGUMENT),
                Arguments.of(HttpStatus.UNAUTHORIZED, CanonicalErrors.UNAUTHENTICATED),
                Arguments.of(HttpStatus.FORBIDDEN, CanonicalErrors.PERMISSION_DENIED),
                Arguments.of(HttpStatus.NOT_FOUND, CanonicalErrors.NOT_FOUND),
                Arguments.of(HttpStatus.GONE, CanonicalErrors.NOT_FOUND),
                Arguments.of(HttpStatus.CONFLICT, CanonicalErrors.CONFLICT),
                Arguments.of(HttpStatus.UNPROCESSABLE_ENTITY, CanonicalErrors.INVALID_ARGUMENT),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, CanonicalErrors.RESOURCE_EXHAUSTED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, CanonicalErrors.INTERNAL),
                Arguments.of(HttpStatus.BAD_GATEWAY, CanonicalErrors.BAD_GATEWAY),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, CanonicalErrors.UNAVAILABLE),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT, CanonicalErrors.DEADLINE_EXCEEDED)
        );
    }
}
