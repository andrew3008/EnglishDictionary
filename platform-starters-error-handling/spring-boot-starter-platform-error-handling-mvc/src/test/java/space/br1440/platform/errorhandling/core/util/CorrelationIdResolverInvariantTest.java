package space.br1440.platform.errorhandling.core.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.lang.Nullable;
import org.springframework.mock.web.MockHttpServletRequest;
import space.br1440.platform.errorhandling.core.constants.ErrorHandlingHeaders;
import space.br1440.platform.errorhandling.properties.ErrorHandlingProperties;

import java.util.SplittableRandom;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("CorrelationIdResolver — инварианты (детерминированная генерация входов)")
class CorrelationIdResolverInvariantTest {

    private static final int ITERATIONS = 1000;

    private static final long SEED_NULL_OR_BLANK = 0xDEAD_BEEF_1L;
    private static final long SEED_WHITELIST = 0xDEAD_BEEF_2L;
    private static final long SEED_CRLF = 0xDEAD_BEEF_3L;
    private static final long SEED_ASCII_PASSTHROUGH = 0xDEAD_BEEF_4L;

    private static final int UUID_STRING_LENGTH = 36;

    private final CorrelationIdResolver resolver = new CorrelationIdResolver(
            new ErrorHandlingProperties().getCorrelationId(),
            true
    );

    private static final Pattern UUID_V4 = Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

    @Test
    @DisplayName("Резолвер всегда возвращает non-blank для произвольного X-Correlation-Id header")
    void totalFunctionality_neverNullOrBlank() {
        // given
        SplittableRandom rng = new SplittableRandom(SEED_NULL_OR_BLANK);
        for (int i = 0; i < ITERATIONS; i++) {
            // given
            String headerValue = randomCorrelationHeaderInput(rng, 2048, 0.05);
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (headerValue != null) {
                request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, headerValue);
            }

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result)
                    .as("Invariant: iteration=%d, seed=%d, input=[%s] (len=%s)",
                            i, SEED_NULL_OR_BLANK, escapeForMessage(headerValue),
                            headerValue == null ? -1 : headerValue.length())
                    .isNotBlank();
        }
    }

    @Test
    @DisplayName("Любой результат соответствует whitelist либо является UUID v4; длина в рамках контракта")
    void result_alwaysWhitelistCompliantOrUuid_withSoundLengthBounds() {
        // given
        SplittableRandom rng = new SplittableRandom(SEED_WHITELIST);
        ErrorHandlingProperties props = new ErrorHandlingProperties();
        Pattern allowed = Pattern.compile(props.getCorrelationId().getAllowedPattern());
        int maxLen = props.getCorrelationId().getMaxLength();

        for (int i = 0; i < ITERATIONS; i++) {
            // given
            String headerValue = randomCorrelationHeaderInput(rng, 512, 0.05);
            MockHttpServletRequest request = new MockHttpServletRequest();
            if (headerValue != null) {
                request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, headerValue);
            }

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            boolean matchesAllowed = allowed.matcher(result).matches();
            boolean matchesUuid = UUID_V4.matcher(result).matches();
            assertThat(matchesAllowed || matchesUuid)
                    .as("result='%s' (matchesAllowed=%s, matchesUuid=%s); iteration=%d, seed=%d, input=[%s]",
                            result, matchesAllowed, matchesUuid, i, SEED_WHITELIST,
                            escapeForMessage(headerValue))
                    .isTrue();

            if (matchesUuid) {
                assertThat(result.length())
                        .as("UUID fallback: iteration=%d, seed=%d, result=%s",
                                i, SEED_WHITELIST, result)
                        .isEqualTo(UUID_STRING_LENGTH);
            } else {
                assertThat(matchesAllowed)
                        .as("Ожидалось принятое по whitelist значение; iteration=%d, result=%s",
                                i, result)
                        .isTrue();
                assertThat(result.length())
                        .as("Принятое значение не длиннее maxLength=%d: iteration=%d, result=%s",
                                maxLen, i, result)
                        .isLessThanOrEqualTo(maxLen);
            }
        }
    }

    @Test
    @DisplayName("CR/LF в input никогда не утекают в результат (включая \\r, \\n по отдельности)")
    void crlfInjection_neverInResult() {
        // given
        SplittableRandom rng = new SplittableRandom(SEED_CRLF);
        for (int i = 0; i < ITERATIONS; i++) {
            // given
            String prefix = randomAsciiPrefix(rng, 1, 64);
            String headerValue = switch (i % 3) {
                case 0 -> prefix + "\r\nINJECTED";
                case 1 -> prefix + "\rONLY";
                default -> prefix + "\nONLY";
            };
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, headerValue);

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result)
                    .as("Invariant (no CR/LF in output): iteration=%d, seed=%d, input=[%s]",
                            i, SEED_CRLF, escapeForMessage(headerValue))
                    .doesNotContain("\r")
                    .doesNotContain("\n");
        }
    }

    @Test
    @DisplayName("Принятый ASCII-only header пропускается без изменений")
    void asciiHeader_acceptedAsIs() {
        // given
        SplittableRandom rng = new SplittableRandom(SEED_ASCII_PASSTHROUGH);
        for (int i = 0; i < ITERATIONS; i++) {
            // given
            String safe = randomAlphanumeric(rng, 1, 64);
            MockHttpServletRequest request = new MockHttpServletRequest();
            request.addHeader(ErrorHandlingHeaders.X_CORRELATION_ID, safe);

            // when
            String result = resolver.resolveFromRequest(request);

            // then
            assertThat(result)
                    .as("Ожидался тот же ASCII идентификатор: iteration=%d, seed=%d, safe=[%s]",
                            i, SEED_ASCII_PASSTHROUGH, escapeForMessage(safe))
                    .isEqualTo(safe);
        }
    }

    @SuppressWarnings("SameParameterValue")
    private static String randomCorrelationHeaderInput(SplittableRandom rng,
                                                       int maxLen,
                                                       double nullProbability) {
        if (rng.nextDouble() < nullProbability) {
            return null;
        }

        int len = rng.nextInt(maxLen + 1);
        if (len == 0) {
            return "";
        }

        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            int kind = rng.nextInt(100);
            if (kind < 70) {
                sb.append((char) rng.nextInt(' ', '~' + 1));
            } else if (kind < 78) {
                sb.append('\0');
            } else if (kind < 86) {
                sb.append((char) (0xD800 + rng.nextInt(0x800)));
            } else if (kind < 92) {
                sb.append(' ');
            } else {
                sb.append(randomBmpNonSurrogate(rng));
            }
        }

        return sb.toString();
    }

    private static char randomBmpNonSurrogate(SplittableRandom rng) {
        int c = rng.nextInt(0x10000);
        if (c >= 0xD800 && c <= 0xDFFF) {
            return randomBmpNonSurrogate(rng);
        }
        return (char) c;
    }

    @SuppressWarnings("SameParameterValue")
    private static String randomAsciiPrefix(SplittableRandom rng, int minLen, int maxLen) {
        int len = minLen + rng.nextInt(maxLen - minLen + 1);
        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append((char) rng.nextInt(0x20, 0x7F));
        }
        return sb.toString();
    }

    @SuppressWarnings("SameParameterValue")
    private static String randomAlphanumeric(SplittableRandom rng, int minLen, int maxLen) {
        String alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789";
        int len = minLen + rng.nextInt(maxLen - minLen + 1);

        StringBuilder sb = new StringBuilder(len);
        for (int i = 0; i < len; i++) {
            sb.append(alphabet.charAt(rng.nextInt(alphabet.length())));
        }

        return sb.toString();
    }

    private static String escapeForMessage(@Nullable String s) {
        if (s == null) {
            return "<null>";
        }

        String escaped = s.replace("\\", "\\\\")
                .replace("\r", "\\r")
                .replace("\n", "\\n")
                .replace("\0", "\\0");
        if (escaped.length() > 200) {
            return escaped.substring(0, 200) + "...(len=" + s.length() + ")";
        }

        return escaped;
    }
}
