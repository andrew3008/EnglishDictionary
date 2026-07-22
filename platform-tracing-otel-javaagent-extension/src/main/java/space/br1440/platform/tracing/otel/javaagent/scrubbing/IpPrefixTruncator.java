package space.br1440.platform.tracing.otel.javaagent.scrubbing;

import space.br1440.platform.tracing.otel.javaagent.utils.Strings;

/**
 * Усечение IP-адресов до подсети (prefix-grouping): IPv4 → {@code /24}, IPv6 → {@code /64}.
 * <p>
 * Сохраняет подсеть для аналитики, скрывая конкретный хост. Обрабатывает краевые случаи:
 * списки {@code x-forwarded-for}, порт ({@code 10.0.0.1:8080}), IPv4-mapped IPv6
 * ({@code ::ffff:192.168.1.1}). Невалидный ввод не вызывает исключений — сигнализируется
 * возвратом {@code null}, что процессор трактует как fallback на MASK + метрика failures.
 */
public final class IpPrefixTruncator {

    private IpPrefixTruncator() {
        // utility-класс
    }

    /**
     * Усекает значение атрибута с IP. Поддерживает список через запятую (x-forwarded-for):
     * каждый элемент обрабатывается отдельно. Возвращает {@code null}, если ни один элемент
     * не распознан как IP (сигнал «не удалось» → MASK).
     */
    public static String truncate(String value) {
        if (Strings.isBlank(value)) {
            return null;
        }
        if (value.indexOf(',') >= 0) {
            String[] parts = value.split(",");
            StringBuilder sb = new StringBuilder(value.length());
            boolean anyOk = false;
            for (int i = 0; i < parts.length; i++) {
                String single = truncateSingle(parts[i].trim());
                if (i > 0) {
                    sb.append(", ");
                }
                if (single != null) {
                    sb.append(single);
                    anyOk = true;
                } else {
                    sb.append(parts[i].trim());
                }
            }
            return anyOk ? sb.toString() : null;
        }
        return truncateSingle(value.trim());
    }

    private static String truncateSingle(String raw) {
        if (raw.isEmpty()) {
            return null;
        }
        String ip = raw;

        // IPv4-mapped IPv6: ::ffff:192.168.1.1 → берём встроенный IPv4.
        int mappedIdx = ip.lastIndexOf("::ffff:");
        if (mappedIdx >= 0 && ip.indexOf('.') > 0) {
            ip = ip.substring(mappedIdx + "::ffff:".length());
        }

        boolean looksIpv6 = ip.indexOf(':') >= 0 && ip.indexOf('.') < 0;

        if (looksIpv6) {
            return truncateIpv6(stripIpv6Brackets(ip));
        }
        // IPv4 (возможно с портом host:port).
        int colon = ip.indexOf(':');
        if (colon >= 0) {
            ip = ip.substring(0, colon);
        }
        return truncateIpv4(ip);
    }

    private static String stripIpv6Brackets(String ip) {
        // Форма [::1]:8080 — убрать скобки и порт.
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) {
                return ip.substring(1, close);
            }
        }
        return ip;
    }

    private static String truncateIpv4(String ip) {
        String[] octets = ip.split("\\.");
        if (octets.length != 4) {
            return null;
        }
        for (int i = 0; i < 4; i++) {
            if (!isOctet(octets[i])) {
                return null;
            }
        }
        // /24: обнуляем последний октет.
        return octets[0] + "." + octets[1] + "." + octets[2] + ".0";
    }

    private static boolean isOctet(String s) {
        if (s.isEmpty() || s.length() > 3) {
            return false;
        }
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
            v = v * 10 + (c - '0');
        }
        return v <= 255;
    }

    private static String truncateIpv6(String ip) {
        // Разбиваем на группы по ':'. Для усечения до /64 оставляем первые 4 группы.
        // Поддержка "::" (компрессия нулей): развёртка не нужна — берём префикс до первых 4 групп.
        String[] groups = ip.split(":", -1);
        // Минимальная валидация: хотя бы 3 разделителя (или присутствие "::").
        if (groups.length < 3 && ip.indexOf("::") < 0) {
            return null;
        }
        StringBuilder prefix = new StringBuilder();
        int taken = 0;
        for (String g : groups) {
            if (taken == 4) {
                break;
            }
            if (g.isEmpty()) {
                // часть компрессии "::" — дальше идут нули, префикс завершён.
                break;
            }
            if (!isHextet(g)) {
                return null;
            }
            if (taken > 0) {
                prefix.append(':');
            }
            prefix.append(g);
            taken++;
        }
        if (taken == 0) {
            return null;
        }
        // Префикс /64 + компрессия остатка.
        return prefix + "::";
    }

    private static boolean isHextet(String s) {
        if (s.isEmpty() || s.length() > 4) {
            return false;
        }
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
