package space.br1440.platform.errorhandling.core.util.http;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class RetryAfterHeaderParserTest {

    @Nested
    @DisplayName("null/blank input")
    class NullAndBlank {

        @ParameterizedTest
        @NullAndEmptySource
        @ValueSource(strings = {"   ", "\t"})
        @DisplayName("returns null for null/blank header")
        void returnsNullForNullOrBlank(String value) {
            // when / then
            assertThat(RetryAfterHeaderParser.parse(value)).isNull();
        }
    }

    @Nested
    @DisplayName("delay-seconds format")
    class DelaySeconds {

        @Test
        @DisplayName("parses valid seconds value")
        void parsesValidSeconds() {
            // when
            Duration result = RetryAfterHeaderParser.parse("120");

            // then
            assertThat(result).isEqualTo(Duration.ofSeconds(120));
        }

        @Test
        @DisplayName("parses zero seconds")
        void parsesZero() {
            // when
            Duration result = RetryAfterHeaderParser.parse("0");

            // then
            assertThat(result).isEqualTo(Duration.ZERO);
        }

        @Test
        @DisplayName("returns null for negative seconds")
        void nullForNegative() {
            // when / then
            assertThat(RetryAfterHeaderParser.parse("-1")).isNull();
        }

        @Test
        @DisplayName("returns null for seconds exceeding 86400 (24h cap)")
        void nullForExceedingMax() {
            // when / then
            assertThat(RetryAfterHeaderParser.parse("86401")).isNull();
        }

        @Test
        @DisplayName("parses boundary value 86400")
        void parsesBoundary() {
            // when
            Duration result = RetryAfterHeaderParser.parse("86400");

            // then
            assertThat(result).isEqualTo(Duration.ofSeconds(86_400));
        }

        @Test
        @DisplayName("trims whitespace around seconds")
        void trimsWhitespace() {
            // when
            Duration result = RetryAfterHeaderParser.parse("  60  ");

            // then
            assertThat(result).isEqualTo(Duration.ofSeconds(60));
        }
    }

    @Nested
    @DisplayName("HTTP-date format (IMF-fixdate)")
    class HttpDate {

        @Test
        @DisplayName("parses valid IMF-fixdate in the future")
        void parsesValidFutureDate() {
            // given
            ZonedDateTime futureTime = ZonedDateTime.now().plusSeconds(300);
            String header = futureTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);

            // when
            Duration result = RetryAfterHeaderParser.parse(header);

            // then
            assertThat(result).isNotNull();
            assertThat(result.getSeconds()).isCloseTo(300, within(5L));
        }

        @Test
        @DisplayName("returns null for date in the past")
        void nullForPastDate() {
            // given
            ZonedDateTime pastTime = ZonedDateTime.now().minusHours(1);
            String header = pastTime.format(DateTimeFormatter.RFC_1123_DATE_TIME);

            // when / then
            assertThat(RetryAfterHeaderParser.parse(header)).isNull();
        }

        @Test
        @DisplayName("returns null for date exceeding 24h cap")
        void nullForFarFutureDate() {
            // given
            ZonedDateTime farFuture = ZonedDateTime.now().plusDays(2);
            String header = farFuture.format(DateTimeFormatter.RFC_1123_DATE_TIME);

            // when / then
            assertThat(RetryAfterHeaderParser.parse(header)).isNull();
        }
    }

    @Nested
    @DisplayName("invalid values")
    class InvalidValues {

        @ParameterizedTest
        @ValueSource(strings = {"abc", "12.5", "not-a-date", "2024-01-01"})
        @DisplayName("returns null for non-parseable values")
        void nullForGarbage(String value) {
            // when / then
            assertThat(RetryAfterHeaderParser.parse(value)).isNull();
        }
    }
}
