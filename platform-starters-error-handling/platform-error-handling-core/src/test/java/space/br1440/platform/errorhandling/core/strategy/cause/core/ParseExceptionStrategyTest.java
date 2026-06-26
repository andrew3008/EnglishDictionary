package space.br1440.platform.errorhandling.core.strategy.cause.core;

import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import space.br1440.platform.errorhandling.core.util.StackHintExtractor;
import space.br1440.platform.errorhandling.extension.RootCauseResolution;
import space.br1440.platform.errorhandling.model.error_detail.ErrorDetail;
import space.br1440.platform.errorhandling.model.error_detail.ErrorInfoDetail;

import java.text.ParseException;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ParseExceptionStrategyTest {

    private final ParseExceptionStrategy strategy =
            new ParseExceptionStrategy(new StackHintExtractor(true, List.of()));

    @Nested
    class SupportsCause {

        @Test
        void supports_NumberFormatException() {
            assertTrue(strategy.supportsCause(new NumberFormatException("For input string: \"abc\"")));
        }

        @Test
        void supports_DateTimeParseException() {
            assertTrue(strategy.supportsCause(
                    new DateTimeParseException("bad date", "not-a-date", 0)));
        }

        @Test
        void supports_jdk_text_ParseException() {
            assertTrue(strategy.supportsCause(new ParseException("bad text", 3)));
        }

        @Test
        void does_not_support_IllegalArgumentException() {
            assertFalse(strategy.supportsCause(new IllegalArgumentException("unrelated")));
        }

        @Test
        void does_not_support_RuntimeException() {
            assertFalse(strategy.supportsCause(new RuntimeException("unrelated")));
        }

        @Test
        void does_not_support_IOException() {
            assertFalse(strategy.supportsCause(new java.io.IOException("io")));
        }
    }

    @Nested
    class HttpMapping {

        @Test
        void numberFormat_mapsTo_500_InternalServerError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new NumberFormatException("For input string: \"xyz\""));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.httpStatus());
        }

        @Test
        void dateTime_mapsTo_500_InternalServerError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new DateTimeParseException("bad", "2024-13-40", 5));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.httpStatus());
        }

        @Test
        void textParse_mapsTo_500_InternalServerError() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ParseException("unparseable", 4));

            assertEquals(HttpStatus.INTERNAL_SERVER_ERROR, res.httpStatus());
        }
    }

    @Nested
    class ReasonAndDomain {

        @Test
        void numberFormat_reason_and_domain() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new NumberFormatException("For input string: \"abc\"")));

            assertEquals("NUMBER_FORMAT", info.getReason());
            assertEquals("parsing", info.getDomain());
        }

        @Test
        void dateTime_reason_and_domain() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new DateTimeParseException("bad", "2024-13-40", 5)));

            assertEquals("DATE_TIME_FORMAT", info.getReason());
            assertEquals("parsing", info.getDomain());
        }

        @Test
        void textParse_reason_and_domain() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new ParseException("bad", 0)));

            assertEquals("TEXT_FORMAT", info.getReason());
            assertEquals("parsing", info.getDomain());
        }
    }

    @Nested
    class ClientMessages {

        @Test
        void numberFormat_has_number_specific_message() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new NumberFormatException("For input string: \"abc\""));

            assertTrue(res.message().toLowerCase().contains("numeric"),
                    "expected numeric-specific message, got: " + res.message());
        }

        @Test
        void dateTime_has_date_time_specific_message() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new DateTimeParseException("bad", "2024-13-40", 5));

            assertTrue(res.message().toLowerCase().contains("date"),
                    "expected date/time-specific message, got: " + res.message());
        }

        @Test
        void textParse_has_textual_specific_message() {
            RootCauseResolution res = strategy.extractCauseDetails(
                    new ParseException("bad", 0));

            assertTrue(res.message().toLowerCase().contains("text"),
                    "expected text-specific message, got: " + res.message());
        }
    }

    @Nested
    class NumberFormatSecurity {

        @Test
        void numberFormat_does_not_leak_raw_input_as_causeMessage_cwe209() {
            NumberFormatException nfe = new NumberFormatException(
                    "For input string: \"pwd123supersecret\"");

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(nfe));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertFalse(diag.containsKey("causeMessage"),
                    "NumberFormatException.getMessage() leaks raw input — must NOT be exposed");
            assertFalse(diag.values().stream().anyMatch(v -> v.contains("pwd123supersecret")),
                    "raw user input leaked into diagnostic metadata: " + diag);
        }

        @Test
        void numberFormat_exposes_only_exceptionClass_and_stackHint() {
            NumberFormatException nfe = new NumberFormatException(
                    "For input string: \"secret\"");

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(nfe));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertTrue(diag.containsKey("exceptionClass"));
            assertTrue(diag.containsKey("stackHint"));
            assertFalse(diag.containsKey("causeMessage"));
            assertFalse(diag.containsKey("inputLength"),
                    "inputLength is misleading (measures JDK message length, not user input) — must be removed");
        }
    }

    @Nested
    class DateTimeMetadata {

        @Test
        void dateTime_records_errorIndex_and_parsedString() {
            DateTimeParseException ex = new DateTimeParseException(
                    "Text '2024-13-40' could not be parsed", "2024-13-40", 5);

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(ex));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertEquals("5", diag.get("errorIndex"));
            assertEquals("2024-13-40", diag.get("parsedString"));
            assertFalse(diag.containsKey("parsedStringLength"),
                    "parsedStringLength duplicates information visible from parsedString — must be removed");
        }

        @Test
        void dateTime_sanitizes_control_chars_in_parsedString_cwe117() {
            DateTimeParseException ex = new DateTimeParseException(
                    "bad", "evil\r\nInjected: ERROR", 0);

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(ex));
            Map<String, String> diag = info.getDiagnosticMetadata();

            String sanitized = diag.get("parsedString");
            assertFalse(sanitized.contains("\r"));
            assertFalse(sanitized.contains("\n"));
        }

        @Test
        void dateTime_without_parsedString_omits_parsedStringKey() {
            DateTimeParseException ex = new DateTimeParseException("bad", "", -1);

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(ex));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertFalse(diag.containsKey("parsedString"),
                    "empty parsedString should not be recorded");
            assertFalse(diag.containsKey("errorIndex"),
                    "negative errorIndex must not be recorded");
        }
    }

    @Nested
    class TextParseMetadata {

        @Test
        void textParse_records_errorOffset() {
            ParseException ex = new ParseException("Unparseable", 7);

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(ex));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertEquals("7", diag.get("errorOffset"));
        }

        @Test
        void textParse_records_truncated_causeMessage() {
            ParseException ex = new ParseException("Unparseable input at position", 7);

            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(ex));
            Map<String, String> diag = info.getDiagnosticMetadata();

            assertTrue(diag.containsKey("causeMessage"));
        }
    }

    @Nested
    class StandardMetadata {

        @Test
        void records_exceptionClass() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new NumberFormatException("x")));

            assertEquals("java.lang.NumberFormatException",
                    info.getDiagnosticMetadata().get("exceptionClass"));
        }

        @Test
        void records_stackHint() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new NumberFormatException("x")));

            assertTrue(info.getDiagnosticMetadata().containsKey("stackHint"));
        }

        @Test
        void diagnosticMetadata_is_unmodifiable() {
            ErrorInfoDetail info = extractInfo(strategy.extractCauseDetails(
                    new NumberFormatException("x")));

            assertThrows(UnsupportedOperationException.class,
                    () -> info.getDiagnosticMetadata().put("k", "v"));
        }
    }

    private static ErrorInfoDetail extractInfo(RootCauseResolution res) {
        return res.details().stream()
                .filter(ErrorInfoDetail.class::isInstance)
                .map(ErrorInfoDetail.class::cast)
                .findFirst()
                .orElseThrow(() -> new AssertionError("ErrorInfoDetail is missing: " + res.details()));
    }

    @SuppressWarnings("unused")
    private static List<ErrorDetail> detailsOf(RootCauseResolution res) {
        return res.details();
    }
}
