package space.br1440.platform.errorhandling.core.strategy.top;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParseException;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.exc.InvalidDefinitionException;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.resolver.CauseChainResolver;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.ErrorHandlingContext;
import space.br1440.platform.errorhandling.extension.LogPolicy;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("JacksonExceptionStrategy")
class JacksonExceptionStrategyTest {

    private JacksonExceptionStrategy strategy;

    private static ErrorHandlingContext context(String path) {
        return ErrorHandlingContext.of(
                new ErrorHandlingProperties(), path, new StackHintExtractor(false, List.of()));
    }

    @BeforeEach
    void setUp() {
        CauseChainResolver resolver = new CauseChainResolver(Collections.emptyList());
        strategy = new JacksonExceptionStrategy(resolver);
    }

    @Nested
    @DisplayName("supports")
    class Supports {

        @Test
        void acceptsJsonProcessingException() {
            // given
            JsonParseException ex = new JsonParseException((JsonParser) null, "Unexpected character");

            // when / then
            assertThat(strategy.supports(ex)).isTrue();
        }

        @Test
        void acceptsJsonMappingException() {
            // given
            MismatchedInputException ex = MismatchedInputException.from(
                    (JsonParser) null, String.class, "Cannot deserialize");

            // when / then
            assertThat(strategy.supports(ex)).isTrue();
        }

        @Test
        void rejectsNonJacksonExceptions() {
            // when / then
            assertThat(strategy.supports(new RuntimeException("not jackson"))).isFalse();
        }
    }

    @Nested
    @DisplayName("handle")
    class Handle {

        @Test
        void jsonParseException_returns400() {
            // given
            JsonParseException ex = new JsonParseException((JsonParser) null,
                    "Unexpected character ('x' (code 120))");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/api/v1/data"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
            assertThat(entry.getErrorCode()).isEqualTo("INVALID_ARGUMENT");
        }

        @Test
        void mismatchedInputException_returns400() {
            // given
            MismatchedInputException ex = MismatchedInputException.from(
                    (JsonParser) null, Integer.class, "Cannot deserialize value of type Integer from String");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getHttpStatus()).isEqualTo(HttpStatus.BAD_REQUEST);
        }

        @Test
        void publicMessage_doesNotContainInternalDetails() {
            // given
            JsonParseException ex = new JsonParseException((JsonParser) null,
                    "Unexpected character at [Source: (ByteArrayInputStream); line: 1, column: 5]");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // then
            assertThat(entry.getMessage()).doesNotContain("ByteArrayInputStream");
        }

        @Test
        void requestPathPreserved() {
            // given
            JsonParseException ex = new JsonParseException((JsonParser) null, "bad json");

            // when
            ErrorEntry entry = strategy.handle(ex, context("/api/v2/orders"));

            // then
            assertThat(entry.getRequestURI()).isEqualTo("/api/v2/orders");
        }
    }

    @Nested
    @DisplayName("logPolicy")
    class LogPolicyTests {

        @Test
        void clientError_returnsWarn() {
            // given
            JsonParseException ex = new JsonParseException((JsonParser) null, "bad");
            ErrorEntry entry = strategy.handle(ex, context("/"));

            // when / then
            assertThat(strategy.logPolicy(ex, entry)).isEqualTo(LogPolicy.WARN);
        }
    }
}
