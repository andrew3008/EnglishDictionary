package space.br1440.platform.errorhandling.core.util.http;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FeignExceptionUtilsTest {

    @Nested
    class StatusMapping {

        @ParameterizedTest
        @CsvSource({
                "401, UNAUTHORIZED",
                "403, FORBIDDEN",
                "404, NOT_FOUND",
                "408, GATEWAY_TIMEOUT",
                "409, CONFLICT",
                "410, GONE",
                "429, TOO_MANY_REQUESTS",
                "503, SERVICE_UNAVAILABLE",
                "504, GATEWAY_TIMEOUT"
        })
        void maps_known_status_codes(int feignStatus, String expected) {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(false, feignStatus);

            // then
            assertEquals(HttpStatus.valueOf(expected), result);
        }

        @Test
        void maps_410_to_gone() {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(false, 410);

            // then
            assertEquals(HttpStatus.GONE, result, "410 Gone should preserve upstream GONE semantics");
        }

        /**
         * Статусы "наша вина" (мы сформировали невалидный запрос) — это бажный код
         * нашего сервиса, а не проблема интеграции: 400/413/414/415/422 → {@code 500}.
         * Маскировка под {@code 502} искажает SLO-дашборды.
         */
        @ParameterizedTest
        @ValueSource(ints = {400, 413, 414, 415, 422})
        void maps_our_bad_request_to_internal_server_error(int feignStatus) {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(false, feignStatus);

            // then
            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, result);
        }

        @ParameterizedTest
        @ValueSource(ints = {405, 500, 501, 502})
        void maps_other_4xx_5xx_to_badGateway(int feignStatus) {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(false, feignStatus);

            // then
            assertEquals(HttpStatus.BAD_GATEWAY, result);
        }

        @Test
        void retryable_without_status_maps_to_serviceUnavailable() {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(true, -1);

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result);
        }

        @Test
        void retryable_without_status_zero_maps_to_serviceUnavailable() {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(true, 0);

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result);
        }

        @Test
        void unknown_status_below_400_maps_to_serviceUnavailable() {
            // when
            HttpStatus result = FeignExceptionUtils.mapFeignStatusToHttpStatus(false, 200);

            // then
            assertEquals(HttpStatus.SERVICE_UNAVAILABLE, result);
        }
    }

    @Nested
    class ErrorCodes {

        @ParameterizedTest
        @CsvSource({
                "UNAUTHORIZED, UNAUTHENTICATED",
                "FORBIDDEN, PERMISSION_DENIED",
                "NOT_FOUND, NOT_FOUND",
                "TOO_MANY_REQUESTS, RESOURCE_EXHAUSTED",
                "BAD_GATEWAY, BAD_GATEWAY",
                "SERVICE_UNAVAILABLE, UNAVAILABLE",
                "GATEWAY_TIMEOUT, DEADLINE_EXCEEDED"
        })
        void determines_correct_error_code(String httpStatus, String expectedCode) {
            // when
            String result = FeignExceptionUtils.determineErrorCode(HttpStatus.valueOf(httpStatus));

            // then
            assertEquals(expectedCode, result);
        }
    }

    @Nested
    class FeignMethodKeyExtraction {

        @Test
        void extracts_methodKey_from_standard_message() {
            // given
            String message = "[503] during [GET] to [http://user-service:8080/api/users/1] " +
                    "[UserClient#getUser(Long)]: [{\"error\":\"unavailable\"}]";

            // when
            Matcher matcher = FeignExceptionUtils.FEIGN_METHOD_KEY.matcher(message);

            // then
            assertTrue(matcher.find());
            assertEquals("UserClient#getUser(Long)", matcher.group(1));
        }

        @Test
        void extracts_methodKey_with_multiple_params() {
            // given
            String message = "[404] during [POST] to [http://svc/api] " +
                    "[OrderClient#createOrder(String, OrderDTO)]: [Not Found]";

            // when
            Matcher matcher = FeignExceptionUtils.FEIGN_METHOD_KEY.matcher(message);

            // then
            assertTrue(matcher.find());
            assertEquals("OrderClient#createOrder(String, OrderDTO)", matcher.group(1));
        }

        @Test
        void extracts_methodKey_with_no_params() {
            // given
            String message = "[200] during [GET] to [http://svc/health] " +
                    "[HealthClient#check()]: [OK]";

            // when
            Matcher matcher = FeignExceptionUtils.FEIGN_METHOD_KEY.matcher(message);

            // then
            assertTrue(matcher.find());
            assertEquals("HealthClient#check()", matcher.group(1));
        }

        @Test
        void extracts_methodKey_with_inner_class() {
            // given
            String message = "[500] during [GET] to [http://svc/api] " +
                    "[Outer$Inner#method(int)]: [error]";

            // when
            Matcher matcher = FeignExceptionUtils.FEIGN_METHOD_KEY.matcher(message);

            // then
            assertTrue(matcher.find());
            assertEquals("Outer$Inner#method(int)", matcher.group(1));
        }

        @Test
        void no_match_on_plain_message() {
            // given
            String message = "Connection refused executing GET http://svc/api";

            // when
            Matcher matcher = FeignExceptionUtils.FEIGN_METHOD_KEY.matcher(message);

            // then
            assertFalse(matcher.find());
        }
    }

    @Nested
    class DiagnosticMetadataWithMockException {

        private final StackHintExtractor stackHintExtractor = new StackHintExtractor(true, List.of());

        @Test
        void includes_exceptionClass() {
            // given
            RuntimeException ex = new RuntimeException("test");

            // when
            Map<String, String> metadata = buildMetadataForNonFeign(ex);

            // then
            assertEquals("java.lang.RuntimeException", metadata.get("exceptionClass"));
        }

        @Test
        void includes_rootCause_when_wrapped() {
            // given
            IllegalStateException root = new IllegalStateException("root problem");
            RuntimeException middle = new RuntimeException("middle", root);
            RuntimeException outer = new RuntimeException("outer", middle);

            // when
            Map<String, String> metadata = buildMetadataForNonFeign(outer);

            // then
            assertEquals("java.lang.IllegalStateException", metadata.get("rootCauseClass"));
            assertNotNull(metadata.get("rootCauseMessage"));
        }

        @Test
        void no_rootCause_when_no_nesting() {
            // given
            RuntimeException ex = new RuntimeException("single");

            // when
            Map<String, String> metadata = buildMetadataForNonFeign(ex);

            // then
            assertFalse(metadata.containsKey("rootCauseClass"));
            assertFalse(metadata.containsKey("rootCauseMessage"));
        }

        @Test
        void includes_stackHint() {
            // given
            RuntimeException ex = new RuntimeException("test");

            // when
            Map<String, String> metadata = buildMetadataForNonFeign(ex);

            // then
            assertTrue(metadata.containsKey("stackHint"));
        }

        @Test
        void metadata_is_unmodifiable() {
            // given
            RuntimeException ex = new RuntimeException("test");
            Map<String, String> metadata = buildMetadataForNonFeign(ex);

            // when / then
            org.junit.jupiter.api.Assertions.assertThrows(
                    UnsupportedOperationException.class,
                    () -> metadata.put("key", "value"));
        }

        private Map<String, String> buildMetadataForNonFeign(Throwable t) {
            return FeignExceptionUtils.buildDiagnosticMetadata(t, stackHintExtractor);
        }
    }

}
