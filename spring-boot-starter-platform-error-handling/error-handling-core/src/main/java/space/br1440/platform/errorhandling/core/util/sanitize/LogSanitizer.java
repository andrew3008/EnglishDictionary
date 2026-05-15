package space.br1440.platform.errorhandling.core.util.sanitize;

/**
 * Санитизация значений, попадающих в лог через placeholders SLF4J.
 *
 * <p>Защита от трёх классов угроз:
 * <ul>
 *   <li><b>CWE-117 Log Injection</b> — нарушитель внедряет CR/LF в request URI или
 *       заголовок, чтобы фабриковать дополнительные строки логов и обманывать парсеры
 *       и SIEM-системы.</li>
 *   <li><b>"Trojan Source" (CVE-2021-42574)</b> — bidirectional override-символы
 *       Unicode переставляют видимый порядок текста в IDE/терминале, маскируя
 *       вредоносные строки.</li>
 *   <li><b>Console-encoding mojibake / "невидимые" code points</b> — произвольные
 *       не-ASCII символы и непечатаемые управляющие коды в логе зависят от
 *       консольной/файловой кодировки и плохо читаются как в IDE, так и в SIEM.
 *       Любой code point вне ASCII printable ({@code 0x20..0x7E}) превращается
 *       в эскейп-последовательность {@code \\uXXXX} (для не-BMP — пара суррогатов).</li>
 * </ul>
 *
 * <p>Дополнительно ограничивается длина выводимого значения для защиты от log-DoS
 * (попыток засорить storage логов мегабайтными строками).
 *
 * <h2>Контракт</h2>
 * <ul>
 *   <li>{@code null} → {@code "<null>"}</li>
 *   <li>пустая строка → {@code ""}</li>
 *   <li>{@code \r}, {@code \n}, {@code \t}, {@code NUL}, U+202A..U+202E,
 *       U+2066..U+2069 — <b>удаляются</b> (CR/LF/TAB/NUL — log-injection;
 *       bidi — Trojan Source).</li>
 *   <li>любой другой символ вне ASCII printable {@code 0x20..0x7E} (в т.ч. CJK,
 *       кириллица, эмодзи, прочие управляющие, half-surrogates) — заменяется
 *       на эскейп {@code \\uXXXX}; для symbol'ов вне BMP записываются обе половины
 *       суррогатной пары как они представлены в Java {@code String}.</li>
 *   <li>truncate до {@link #DEFAULT_MAX_LENGTH} <b>выходных</b> символов с
 *       маркером {@code "[TRUNCATED]"} в конце.</li>
 * </ul>
 *
 * <h2>Зависимости</h2>
 * <p>Класс намеренно не зависит от Spring или какой-либо внешней библиотеки —
 * это portable utility, пригодный к переиспользованию в WebFlux, gRPC и любом
 * другом транспорте без переписывания. Архитектурное правило ArchUnit фиксирует
 * запрет на любые зависимости вне {@code java..} для этого файла.
 */
public final class LogSanitizer {

    /** Маркер, добавляемый в конец усечённого значения. */
    public static final String TRUNCATION_MARKER = "[TRUNCATED]";

    /** Маркер для null-значений (вместо литерального {@code "null"}, который может ввести в заблуждение). */
    public static final String NULL_MARKER = "<null>";

    /** Максимальная длина sanitized-результата по умолчанию (без учёта маркера). */
    public static final int DEFAULT_MAX_LENGTH = 512;

    /**
     * Длина одной {@code \\uXXXX}-последовательности. Используется для оценки ёмкости
     * и расчёта оставшегося бюджета символов перед записью эскейпа.
     */
    private static final int UNICODE_ESCAPE_LENGTH = 6;

    private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

    private LogSanitizer() {
        throw new AssertionError("LogSanitizer — utility-класс, инстанцирование запрещено.");
    }

    /**
     * Санитизирует строку для безопасного включения в SLF4J-лог.
     *
     * @param value исходное значение (может быть {@code null})
     * @return безопасная для лога строка, никогда {@code null}
     */
    public static String sanitize(String value) {
        return sanitize(value, DEFAULT_MAX_LENGTH);
    }

    /**
     * Санитизирует строку с заданным лимитом длины.
     *
     * @param value     исходное значение (может быть {@code null})
     * @param maxLength максимальное количество <b>выходных</b> символов в результате
     *                  (до маркера {@code [TRUNCATED]})
     * @return безопасная для лога строка, никогда {@code null}
     * @throws IllegalArgumentException если {@code maxLength <= 0}
     */
    public static String sanitize(String value, int maxLength) {
        if (maxLength <= 0) {
            throw new IllegalArgumentException("maxLength must be positive, got: " + maxLength);
        }

        if (value == null) {
            return NULL_MARKER;
        }

        if (value.isEmpty()) {
            return "";
        }

        // Резервируем чуть больше входной длины: в худшем случае каждый non-ASCII
        // превращается в 6 символов (\\uXXXX). Ограничиваем верхней границей, чтобы
        // не аллоцировать мегабайты ради короткого результата.
        int initialCapacity = Math.min(value.length() * UNICODE_ESCAPE_LENGTH, maxLength + UNICODE_ESCAPE_LENGTH);
        StringBuilder sb = new StringBuilder(initialCapacity);
        int kept = 0;
        boolean truncated = false;

        for (int i = 0, n = value.length(); i < n; i++) {
            char c = value.charAt(i);

            if (isStrippedSilently(c)) {
                continue;
            }

            if (isAsciiPrintable(c)) {
                if (kept >= maxLength) {
                    truncated = true;
                    break;
                }

                sb.append(c);
                kept++;
                continue;
            }

            if (kept + UNICODE_ESCAPE_LENGTH > maxLength) {
                truncated = true;
                break;
            }

            appendUnicodeEscape(sb, c);
            kept += UNICODE_ESCAPE_LENGTH;
        }

        if (truncated) {
            sb.append(TRUNCATION_MARKER);
        }

        return sb.toString();
    }

    /**
     * Символы, которые тихо удаляются (без эскейпа): источники log-injection
     * (CR/LF/TAB/NUL) и Unicode bidirectional override (Trojan Source).
     *
     * <p>Их специально <b>не</b> заменяем на {@code \\uXXXX}, чтобы не сохранять
     * в логе следы попытки атаки в виде «ровной» текстовой последовательности
     * — журналу/SIEM такие последовательности тоже подозрительны.
     */
    private static boolean isStrippedSilently(char c) {
        if (c == '\r' || c == '\n' || c == '\t' || c == '\u0000') {
            return true;
        }

        if (c >= '\u202A' && c <= '\u202E') {
            return true;
        }

        return c >= '\u2066' && c <= '\u2069';
    }

    /**
     * ASCII printable: пробел..тильда. Всё, что выходит за этот диапазон,
     * заменяется на {@code \\uXXXX} — независимо от того, читаемо ли это
     * на конкретной консоли.
     */
    private static boolean isAsciiPrintable(char c) {
        return c >= 0x20 && c <= 0x7E;
    }

    private static void appendUnicodeEscape(StringBuilder sb, char c) {
        sb.append('\\').append('u');
        sb.append(HEX_DIGITS[(c >>> 12) & 0xF]);
        sb.append(HEX_DIGITS[(c >>> 8) & 0xF]);
        sb.append(HEX_DIGITS[(c >>> 4) & 0xF]);
        sb.append(HEX_DIGITS[c & 0xF]);
    }
}
