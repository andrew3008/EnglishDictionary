package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdResolver")
class CorrelationIdResolverTest {

    private final CorrelationIdResolver resolver = new CorrelationIdResolver(
            new ErrorHandlingProperties.CorrelationId(),
            true
    );

    @Nested
    @DisplayName("Приоритет заголовков")
    class HeaderPriority {

        @Test
        @DisplayName("X-Correlation-Id имеет наивысший приоритет")
        void correlationIdHeader_hasHighestPriority() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "corr-123");
            request.addHeader("X-Request-Id", "req-456");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isEqualTo("corr-123");
        }

        @Test
        @DisplayName("X-Request-Id используется при отсутствии X-Correlation-Id")
        void requestIdHeader_usedWhenCorrelationIdMissing() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Request-Id", "req-456");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isEqualTo("req-456");
        }

        @Test
        @DisplayName("Генерируется UUID при отсутствии обоих заголовков")
        void uuid_generatedWhenNoHeaders() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result)
                    .isNotBlank()
                    .satisfies(id -> assertThat(UUID.fromString(id)).isNotNull());
        }
    }

    @Nested
    @DisplayName("Пустые значения заголовков")
    class BlankHeaders {

        @Test
        @DisplayName("Пустой X-Correlation-Id игнорируется, используется X-Request-Id")
        void emptyCorrelationId_fallsBackToRequestId() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "");
            request.addHeader("X-Request-Id", "req-789");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isEqualTo("req-789");
        }

        @Test
        @DisplayName("Whitespace-only X-Correlation-Id игнорируется")
        void whitespaceCorrelationId_fallsBackToRequestId() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "   ");
            request.addHeader("X-Request-Id", "req-111");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isEqualTo("req-111");
        }

        @Test
        @DisplayName("Оба заголовка пустые -- генерируется UUID")
        void bothHeadersEmpty_generatesUuid() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader("X-Correlation-Id", "");
            request.addHeader("X-Request-Id", "");

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result)
                    .isNotBlank()
                    .satisfies(id -> assertThat(UUID.fromString(id)).isNotNull());
        }
    }

    @Nested
    @DisplayName("Контракт результата")
    class ResultContract {

        @Test
        @DisplayName("Результат никогда не null")
        void result_neverNull() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isNotNull();
        }

        @Test
        @DisplayName("Результат никогда не пустая строка")
        void result_neverEmpty() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result).isNotEmpty();
        }

        @Test
        @DisplayName("Каждый вызов без заголовков генерирует уникальный UUID")
        void eachCall_generatesUniqueUuid() {
            // given
            MockHttpServletRequest request = new MockHttpServletRequest();

            // when
            String first = resolver.resolveFromRequest(request);
            String second = resolver.resolveFromRequest(request);

            // then
            assertThat(first).isNotEqualTo(second);
        }
    }
}
