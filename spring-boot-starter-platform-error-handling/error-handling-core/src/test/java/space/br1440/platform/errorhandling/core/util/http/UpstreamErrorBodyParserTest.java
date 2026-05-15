package space.br1440.platform.errorhandling.core.util.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.model.ErrorEntry;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class UpstreamErrorBodyParserTest {

    @Nested
    @DisplayName("parse()")
    class Parse {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("returns empty for null/blank body")
        void returnsEmptyForNullOrBlank(String body) {
            // when / then
            assertThat(UpstreamErrorBodyParser.parse(body, true)).isEmpty();
        }

        @Test
        @DisplayName("parses valid body with all fields")
        void parsesFullBody() {
            // given
            String json = """
                    {"errorCode": "ORD-0042", "reason": "not_found", "domain": "orders", "message": "secret"}
                    """;

            // when
            Optional<UpstreamErrorBodyParser.UpstreamErrorInfo> result = UpstreamErrorBodyParser.parse(json, true);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().errorCode()).isEqualTo("ORD-0042");
            assertThat(result.get().reason()).isEqualTo("not_found");
            assertThat(result.get().domain()).isEqualTo("orders");
        }

        @Test
        @DisplayName("omits reason when includeReason=false")
        void omitsReasonWhenFlagDisabled() {
            // given
            String json = """
                    {"errorCode": "ORD-0042", "reason": "not_found", "domain": "orders"}
                    """;

            // when
            Optional<UpstreamErrorBodyParser.UpstreamErrorInfo> result = UpstreamErrorBodyParser.parse(json, false);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().errorCode()).isEqualTo("ORD-0042");
            assertThat(result.get().reason()).isNull();
            assertThat(result.get().domain()).isEqualTo("orders");
        }

        @Test
        @DisplayName("returns empty when errorCode is missing")
        void emptyWhenNoErrorCode() {
            // given
            String json = """
                    {"reason": "not_found", "domain": "orders"}
                    """;

            // when / then
            assertThat(UpstreamErrorBodyParser.parse(json, true)).isEmpty();
        }

        @Test
        @DisplayName("returns empty when errorCode is blank")
        void emptyWhenErrorCodeBlank() {
            // given
            String json = """
                    {"errorCode": "   "}
                    """;

            // when / then
            assertThat(UpstreamErrorBodyParser.parse(json, true)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for non-object JSON (array)")
        void emptyForJsonArray() {
            // when / then
            assertThat(UpstreamErrorBodyParser.parse("[1,2,3]", true)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for invalid JSON")
        void emptyForInvalidJson() {
            // when / then
            assertThat(UpstreamErrorBodyParser.parse("{broken json", true)).isEmpty();
        }

        @Test
        @DisplayName("returns empty for body exceeding MAX_BODY_PARSE_SIZE")
        void emptyForOversizedBody() {
            // given
            String oversized = "{\"errorCode\": \"X\"" + "x".repeat(UpstreamErrorBodyParser.MAX_BODY_PARSE_SIZE) + "}";

            // when / then
            assertThat(UpstreamErrorBodyParser.parse(oversized, true)).isEmpty();
        }

        @Test
        @DisplayName("truncates errorCode exceeding MAX_FIELD_LENGTH")
        void truncatesLongErrorCode() {
            // given
            String longCode = "A".repeat(300);
            String json = String.format("{\"errorCode\": \"%s\"}", longCode);

            // when
            Optional<UpstreamErrorBodyParser.UpstreamErrorInfo> result = UpstreamErrorBodyParser.parse(json, true);

            // then
            assertThat(result).isPresent();
            String errorCode = result.get().errorCode();
            assertThat(errorCode)
                    .startsWith("A".repeat(UpstreamErrorBodyParser.MAX_FIELD_LENGTH))
                    .contains("[TRUNCATED]");
        }

        @Test
        @DisplayName("CWE-209: message field is never proxied")
        void messageNeverProxied() {
            // given
            String json = """
                    {"errorCode": "ERR-001", "message": "SELECT * FROM users WHERE password='secret'"}
                    """;

            // when
            Optional<UpstreamErrorBodyParser.UpstreamErrorInfo> result = UpstreamErrorBodyParser.parse(json, true);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().errorCode()).isEqualTo("ERR-001");
            assertThat(result.get().reason()).isNull();
            assertThat(result.get().domain()).isNull();
        }

        @Test
        @DisplayName("handles extra unknown fields gracefully")
        void ignoresUnknownFields() {
            // given
            String json = """
                    {"errorCode": "ERR-001", "unknownField": "value", "nested": {"a": 1}}
                    """;

            // when
            Optional<UpstreamErrorBodyParser.UpstreamErrorInfo> result = UpstreamErrorBodyParser.parse(json, true);

            // then
            assertThat(result).isPresent();
            assertThat(result.get().errorCode()).isEqualTo("ERR-001");
        }

        @Test
        @DisplayName("handles non-string errorCode gracefully")
        void emptyForNonStringErrorCode() {
            // given
            String json = """
                    {"errorCode": 42}
                    """;

            // when / then
            assertThat(UpstreamErrorBodyParser.parse(json, true)).isEmpty();
        }
    }

    @Nested
    @DisplayName("applyUpstreamInfo()")
    class ApplyUpstreamInfo {

        private ErrorEntry baseEntry() {
            return ErrorEntry.builder()
                    .httpStatus(HttpStatus.BAD_GATEWAY)
                    .errorCode("DEFAULT-001")
                    .message("base message")
                    .build();
        }

        @Test
        @DisplayName("applies errorCode from upstream info")
        void appliesErrorCode() {
            // given
            var info = new UpstreamErrorBodyParser.UpstreamErrorInfo("ORD-042", null, null);

            // when
            ErrorEntry result = UpstreamErrorBodyParser.applyUpstreamInfo(baseEntry(), info);

            // then
            assertThat(result.getErrorCode()).isEqualTo("ORD-042");
            assertThat(result.getMessage()).isEqualTo("base message");
        }

        @Test
        @DisplayName("applies reason and domain when present")
        void appliesReasonAndDomain() {
            // given
            var info = new UpstreamErrorBodyParser.UpstreamErrorInfo("ORD-042", "not_found", "orders");

            // when
            ErrorEntry result = UpstreamErrorBodyParser.applyUpstreamInfo(baseEntry(), info);

            // then
            assertThat(result.getErrorCode()).isEqualTo("ORD-042");
            assertThat(result.getReason()).isEqualTo("not_found");
            assertThat(result.getDomain()).isEqualTo("orders");
        }

        @Test
        @DisplayName("preserves base entry fields when info fields are null")
        void preservesBaseWhenInfoNull() {
            // given
            ErrorEntry base = baseEntry().toBuilder().reason("original").domain("base").build();
            var info = new UpstreamErrorBodyParser.UpstreamErrorInfo("NEW-001", null, null);

            // when
            ErrorEntry result = UpstreamErrorBodyParser.applyUpstreamInfo(base, info);

            // then
            assertThat(result.getErrorCode()).isEqualTo("NEW-001");
            assertThat(result.getReason()).isEqualTo("original");
            assertThat(result.getDomain()).isEqualTo("base");
        }
    }
}
