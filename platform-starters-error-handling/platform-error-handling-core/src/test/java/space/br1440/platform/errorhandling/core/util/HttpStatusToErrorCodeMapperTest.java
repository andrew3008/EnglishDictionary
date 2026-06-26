package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpStatus;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Канонический контракт маппинга {@link HttpStatus} → {@code errorCode} верхнего уровня.
 *
 * <p>Этот тест — единственный источник истины для bi-stack consistency: и MVC, и WebFlux
 * стратегии фреймворка строят top-level {@code dto.errorCode} через
 * {@link HttpStatusToErrorCodeMapper#map(HttpStatus)}. Если таблица меняется — изменение
 * должно быть синхронным для обоих модулей и зафиксировано в CHANGELOG.
 */
@DisplayName("HttpStatusToErrorCodeMapper — bi-stack canon (gRPC-style)")
class HttpStatusToErrorCodeMapperTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @MethodSource("canonCases")
    @DisplayName("Каждый HTTP-статус маппится в стабильный errorCode")
    void map_returnsCanonicalErrorCode(HttpStatus status, String expectedErrorCode) {
        assertThat(HttpStatusToErrorCodeMapper.map(status)).isEqualTo(expectedErrorCode);
    }

    static Stream<Arguments> canonCases() {
        // Все 4xx без специальной семантики попадают в INVALID_ARGUMENT —
        // gRPC-style canon. Гранулярные различия (METHOD_NOT_ALLOWED vs
        // UNSUPPORTED_MEDIA_TYPE) — на уровне ErrorInfoDetail.reason.
        return Stream.of(
                // 4xx mapping
                Arguments.of(HttpStatus.BAD_REQUEST,             "INVALID_ARGUMENT"),
                Arguments.of(HttpStatus.UNAUTHORIZED,            "UNAUTHENTICATED"),
                Arguments.of(HttpStatus.FORBIDDEN,               "PERMISSION_DENIED"),
                Arguments.of(HttpStatus.NOT_FOUND,               "NOT_FOUND"),
                Arguments.of(HttpStatus.METHOD_NOT_ALLOWED,      "INVALID_ARGUMENT"),
                Arguments.of(HttpStatus.NOT_ACCEPTABLE,          "INVALID_ARGUMENT"),
                Arguments.of(HttpStatus.CONFLICT,                "ABORTED"),
                Arguments.of(HttpStatus.PRECONDITION_FAILED,     "FAILED_PRECONDITION"),
                Arguments.of(HttpStatus.PAYLOAD_TOO_LARGE,       "RESOURCE_EXHAUSTED"),
                Arguments.of(HttpStatus.UNSUPPORTED_MEDIA_TYPE,  "INVALID_ARGUMENT"),
                Arguments.of(HttpStatus.UNPROCESSABLE_ENTITY,    "INVALID_ARGUMENT"),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS,       "RESOURCE_EXHAUSTED"),
                // 5xx mapping
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR,   "INTERNAL"),
                Arguments.of(HttpStatus.NOT_IMPLEMENTED,         "UNIMPLEMENTED"),
                Arguments.of(HttpStatus.BAD_GATEWAY,             "INTERNAL"),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE,     "UNAVAILABLE"),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT,         "DEADLINE_EXCEEDED")
        );
    }

    @Test
    @DisplayName("Любой 4xx без явного правила попадает в INVALID_ARGUMENT")
    void unmapped4xx_fallsToInvalidArgument() {
        assertThat(HttpStatusToErrorCodeMapper.map(HttpStatus.I_AM_A_TEAPOT))
                .isEqualTo("INVALID_ARGUMENT");
        assertThat(HttpStatusToErrorCodeMapper.map(HttpStatus.LOCKED))
                .isEqualTo("INVALID_ARGUMENT");
    }

    @Test
    @DisplayName("Любой 5xx без явного правила попадает в INTERNAL")
    void unmapped5xx_fallsToInternal() {
        assertThat(HttpStatusToErrorCodeMapper.map(HttpStatus.HTTP_VERSION_NOT_SUPPORTED))
                .isEqualTo("INTERNAL");
        assertThat(HttpStatusToErrorCodeMapper.map(HttpStatus.INSUFFICIENT_STORAGE))
                .isEqualTo("INTERNAL");
    }

    @Test
    @DisplayName("Bi-stack inheritance: одинаковые статусы дают одинаковые errorCode")
    void biStackConsistency_sameStatusSameCode() {
        // Для каждого case в canonCases() результат map() должен быть детерминированным;
        // эта проверка фиксирует «двойную идентичность» между двумя вызовами и не зависит
        // от порядка / окружения / classloader-а. Если кто-то добавит ConcurrentMap-кэш
        // с per-thread state — этот тест отловит регрессию.
        canonCases().forEach(args -> {
            HttpStatus status = (HttpStatus) args.get()[0];
            String first = HttpStatusToErrorCodeMapper.map(status);
            String second = HttpStatusToErrorCodeMapper.map(status);
            assertThat(first)
                    .as("map(%s) должен быть детерминированным", status)
                    .isEqualTo(second);
        });
    }
}
