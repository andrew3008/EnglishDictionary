package space.br1440.platform.errorhandling.core.util.sanitize;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("LogSanitizer")
class LogSanitizerTest {

    @Nested
    @DisplayName("Контракт null/empty")
    class NullEmptyContract {

        @Test
        @DisplayName("null → <null>")
        void nullValue_returnsMarker() {
            assertThat(LogSanitizer.sanitize(null)).isEqualTo(LogSanitizer.NULL_MARKER);
        }

        @Test
        @DisplayName("Пустая строка → пустая строка (без TRUNCATED-маркера)")
        void emptyString_returnsEmpty() {
            assertThat(LogSanitizer.sanitize("")).isEqualTo("");
        }

        @Test
        @DisplayName("Обычная безопасная строка проходит без изменений")
        void plainString_unchanged() {
            assertThat(LogSanitizer.sanitize("hello world 123 -.:_/")).isEqualTo("hello world 123 -.:_/");
        }
    }

    @Nested
    @DisplayName("Удаление управляющих символов (CWE-117)")
    class ControlCharacters {

        @Test
        @DisplayName("CR удаляется")
        void carriageReturn_removed() {
            assertThat(LogSanitizer.sanitize("line1\rline2")).isEqualTo("line1line2");
        }

        @Test
        @DisplayName("LF удаляется")
        void lineFeed_removed() {
            assertThat(LogSanitizer.sanitize("line1\nline2")).isEqualTo("line1line2");
        }

        @Test
        @DisplayName("CRLF (классическая попытка smuggling) удаляется")
        void crlf_removed() {
            assertThat(LogSanitizer.sanitize("value\r\nInjected: yes"))
                    .isEqualTo("valueInjected: yes");
        }

        @Test
        @DisplayName("TAB удаляется (защита от парсеров с tab-разделёнными колонками)")
        void tab_removed() {
            assertThat(LogSanitizer.sanitize("a\tb")).isEqualTo("ab");
        }

        @Test
        @DisplayName("NUL удаляется")
        void nul_removed() {
            assertThat(LogSanitizer.sanitize("a\u0000b")).isEqualTo("ab");
        }
    }

    @Nested
    @DisplayName("Unicode bidirectional override (CVE-2021-42574 Trojan Source)")
    class BidiOverride {

        @Test
        @DisplayName("U+202E (RLO) удаляется")
        void rlo_removed() {
            assertThat(LogSanitizer.sanitize("safe\u202Eevil")).isEqualTo("safeevil");
        }

        @Test
        @DisplayName("Все символы U+202A..U+202E удаляются")
        void allLegacyOverrides_removed() {
            String input = "x\u202Aa\u202Bb\u202Cc\u202Dd\u202Ee";
            assertThat(LogSanitizer.sanitize(input)).isEqualTo("xabcde");
        }

        @Test
        @DisplayName("Все символы U+2066..U+2069 (LRI/RLI/FSI/PDI) удаляются")
        void allIsolates_removed() {
            String input = "x\u2066a\u2067b\u2068c\u2069";
            assertThat(LogSanitizer.sanitize(input)).isEqualTo("xabc");
        }
    }

    @Nested
    @DisplayName("Не-ASCII символы эскейпятся в \\uXXXX")
    class NonAsciiEscape {

        @Test
        @DisplayName("Кириллица заменяется на \\uXXXX-последовательности")
        void cyrillic_escaped() {
            // given
            String input = "\u041F\u0440\u0438\u0432\u0435\u0442"; // "Привет"

            // when
            String result = LogSanitizer.sanitize(input);

            // then
            assertThat(result).isEqualTo("\\u041f\\u0440\\u0438\\u0432\\u0435\\u0442");
        }

        @Test
        @DisplayName("CJK code points заменяются на \\uXXXX-последовательности")
        void cjk_escaped() {
            // given
            String input = "\u4E2D\u6587"; // 中文

            // when
            String result = LogSanitizer.sanitize(input);

            // then
            assertThat(result).isEqualTo("\\u4e2d\\u6587");
        }

        @Test
        @DisplayName("ASCII перемежается с не-ASCII: ASCII сохраняется, прочее эскейпится")
        void mixed_asciiKeptNonAsciiEscaped() {
            // when
            String result = LogSanitizer.sanitize("cid-\u041F-42");

            // then
            assertThat(result).isEqualTo("cid-\\u041f-42");
        }

        @Test
        @DisplayName("Символ вне BMP (эмодзи) кодируется суррогатной парой как два \\uXXXX")
        void supplementaryPlane_escapedAsSurrogatePair() {
            // given
            String emoji = new String(Character.toChars(0x1F600)); // 😀

            // when
            String result = LogSanitizer.sanitize(emoji);

            // then
            assertThat(result).isEqualTo("\\ud83d\\ude00");
        }

        @Test
        @DisplayName("Управляющие коды вне CR/LF/TAB/NUL (например U+0007 BEL) эскейпятся")
        void otherControlCharacters_escaped() {
            // when
            String result = LogSanitizer.sanitize("a\u0007b");

            // then
            assertThat(result).isEqualTo("a\\u0007b");
        }

        @Test
        @DisplayName("DEL (U+007F) эскейпится — не входит в ASCII printable 0x20..0x7E")
        void delCharacter_escaped() {
            // when
            String result = LogSanitizer.sanitize("a\u007Fb");

            // then
            assertThat(result).isEqualTo("a\\u007fb");
        }
    }

    @Nested
    @DisplayName("Truncate")
    class Truncate {

        @Test
        @DisplayName("Строка длиннее лимита обрезается с маркером")
        void longString_truncatedWithMarker() {
            String longStr = "a".repeat(600);
            String result = LogSanitizer.sanitize(longStr);
            assertThat(result).hasSize(512 + LogSanitizer.TRUNCATION_MARKER.length());
            assertThat(result).endsWith(LogSanitizer.TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("Строка ровно по лимиту НЕ помечается TRUNCATED")
        void exactlyAtLimit_notTruncated() {
            String exactly = "b".repeat(LogSanitizer.DEFAULT_MAX_LENGTH);
            String result = LogSanitizer.sanitize(exactly);
            assertThat(result).isEqualTo(exactly);
            assertThat(result).doesNotContain(LogSanitizer.TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("Кастомный лимит работает")
        void customLimit_applied() {
            String result = LogSanitizer.sanitize("abcdefgh", 4);
            assertThat(result).isEqualTo("abcd" + LogSanitizer.TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("Эскейп не разрывается: если \\uXXXX не помещается в остаток лимита — TRUNCATED")
        void unicodeEscape_notSplitAcrossLimit() {
            // given
            // лимит 4: ASCII 'a' занимает 1, далее \u041f требует 6 — не влезает,
            // ожидаем "a[TRUNCATED]"
            String input = "a\u041Fb";

            // when
            String result = LogSanitizer.sanitize(input, 4);

            // then
            assertThat(result).isEqualTo("a" + LogSanitizer.TRUNCATION_MARKER);
        }

        @Test
        @DisplayName("maxLength <= 0 — IllegalArgumentException")
        void invalidLimit_throws() {
            assertThatThrownBy(() -> LogSanitizer.sanitize("x", 0))
                    .isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> LogSanitizer.sanitize("x", -1))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Nested
    @DisplayName("Безопасность инстанцирования")
    class Instantiation {

        @Test
        @DisplayName("Конструктор приватный и кидает AssertionError при reflective-инстанцировании")
        void utilityClass_cannotBeInstantiated() throws Exception {
            var ctor = LogSanitizer.class.getDeclaredConstructor();
            ctor.setAccessible(true);
            assertThatThrownBy(ctor::newInstance)
                    .hasCauseInstanceOf(AssertionError.class);
        }
    }
}
