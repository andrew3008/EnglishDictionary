package space.br1440.platform.tracing.otel.extension.scrubbing;

import java.util.Locale;

/**
 * Лёгкое сопоставление имён атрибутов с токенами чувствительных ключей — без {@code regex}
 * и {@code Stream} на горячем пути (вызывается на каждый атрибут каждого span'а).
 * <p>
 * <b>Нормализация.</b> Имя ключа приводится к нижнему регистру, из него удаляются разделители
 * {@code '-'}, {@code '_'}, {@code '.'} и пробелы. Это делает эквивалентными все варианты записи
 * одного логического ключа: {@code client-secret}, {@code client_secret}, {@code client.secret},
 * {@code CLIENT_SECRET}, {@code clientSecret} → {@code clientsecret}. Токены-образцы должны
 * передаваться уже нормализованными (без разделителей, в нижнем регистре).
 */
final class KeyMatcher {

    private KeyMatcher() {
        // utility-класс
    }

    /**
     * Нормализует имя ключа: нижний регистр + удаление разделителей {@code -_.} и пробелов.
     */
    static String normalize(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        String lowered = key.toLowerCase(Locale.ROOT);
        StringBuilder sb = new StringBuilder(lowered.length());
        for (int i = 0; i < lowered.length(); i++) {
            char c = lowered.charAt(i);
            if (c == '-' || c == '_' || c == '.' || c == ' ') {
                continue;
            }
            sb.append(c);
        }
        return sb.toString();
    }

    /**
     * Проверяет, содержит ли нормализованное имя ключа хотя бы один из (нормализованных) токенов.
     */
    static boolean containsAny(String normalizedKey, String... normalizedTokens) {
        if (normalizedKey.isEmpty()) {
            return false;
        }
        for (String token : normalizedTokens) {
            if (!token.isEmpty() && normalizedKey.contains(token)) {
                return true;
            }
        }
        return false;
    }
}
