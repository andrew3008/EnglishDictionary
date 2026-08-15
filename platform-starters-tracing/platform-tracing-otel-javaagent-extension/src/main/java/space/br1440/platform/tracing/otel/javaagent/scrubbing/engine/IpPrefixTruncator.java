package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import lombok.experimental.UtilityClass;

import space.br1440.platform.common.util.StringUtils;

/**
 * Усечение IP-адресов до подсети (prefix-grouping): IPv4 → {@code /24}, IPv6 → {@code /64}.
 * <p>
 * Сохраняет подсеть для аналитики, скрывая конкретный хост. Обрабатывает краевые случаи:
 * списки {@code x-forwarded-for}, порт ({@code 10.0.0.1:8080}), IPv4-mapped IPv6
 * ({@code ::ffff:192.168.1.1}). Невалидный ввод не вызывает исключений — сигнализируется
 * возвратом {@code null}, что процессор трактует как fallback на MASK + метрика failures.
 */
@UtilityClass
public final class IpPrefixTruncator {

    public static String truncate(String value) {
        if (StringUtils.isNullOrBlank(value)) {
            return null;
        }

        if (value.indexOf(',') >= 0) {
            String[] parts = value.split(",", -1);
            StringBuilder sb = new StringBuilder(value.length());
            for (int i = 0; i < parts.length; i++) {
                String single = truncateSingle(parts[i].trim());
                if (single == null) {
                    return null;
                }

                if (i > 0) {
                    sb.append(", ");
                }

                sb.append(single);
            }

            return sb.toString();
        }

        return truncateSingle(value.trim());
    }

    private static String truncateSingle(String raw) {
        if (raw.isEmpty()) {
            return null;
        }

        String ip = raw;
        int mappedIdx = ip.lastIndexOf("::ffff:");
        if (mappedIdx >= 0 && ip.indexOf('.') > 0) {
            ip = ip.substring(mappedIdx + "::ffff:".length());
        }

        boolean looksIpv6 = ip.indexOf(':') >= 0 && ip.indexOf('.') < 0;
        if (looksIpv6) {
            return truncateIpv6(stripIpv6Brackets(ip));
        }

        int colon = ip.indexOf(':');
        if (colon >= 0) {
            ip = ip.substring(0, colon);
        }

        return truncateIpv4(ip);
    }

    private static String stripIpv6Brackets(String ip) {
        if (ip.startsWith("[")) {
            int close = ip.indexOf(']');
            if (close > 0) {
                return ip.substring(1, close);
            }
        }

        return ip;
    }

    private static String truncateIpv4(String ip) {
        int len = ip.length();
        int octetStart = 0;
        int octetIndex = 0;
        int firstDot = -1;
        int secondDot = -1;
        int thirdDot = -1;

        for (int i = 0; i <= len; i++) {
            boolean atEnd = i == len;
            char c = atEnd ? '.' : ip.charAt(i);
            if (c != '.') {
                continue;
            }

            if (octetIndex >= 4) {
                return null;
            }

            if (!isOctet(ip, octetStart, i)) {
                return null;
            }

            if (octetIndex == 0) {
                firstDot = i;
            } else if (octetIndex == 1) {
                secondDot = i;
            } else if (octetIndex == 2) {
                thirdDot = i;
            }

            octetIndex++;
            octetStart = (i + 1);
        }

        if (octetIndex != 4) {
            return null;
        }

        return ip.substring(0, firstDot) + '.'
                + ip.substring(firstDot + 1, secondDot) + '.'
                + ip.substring(secondDot + 1, thirdDot) + ".0";
    }

    private static boolean isOctet(String s, int from, int to) {
        int length = to - from;
        if (length <= 0 || length > 3) {
            return false;
        }

        int v = 0;
        for (int i = from; i < to; i++) {
            char c = s.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }

            v = (v * 10) + (c - '0');
        }

        return (v <= 255);
    }

    private static String truncateIpv6(String ip) {
        int compression = ip.indexOf("::");
        if (compression != ip.lastIndexOf("::")) {
            return null;
        }

        String left = compression >= 0 ? ip.substring(0, compression) : ip;
        String right = compression >= 0 ? ip.substring(compression + 2) : "";

        String[] leftGroups = left.isEmpty() ? new String[0] : left.split(":", -1);
        String[] rightGroups = right.isEmpty() ? new String[0] : right.split(":", -1);

        int explicitGroups = leftGroups.length + rightGroups.length;
        int zeroGroups = compression >= 0 ? 8 - explicitGroups : 0;
        if ((compression >= 0 && zeroGroups < 1) || (compression < 0 && explicitGroups != 8)) {
            return null;
        }

        int[] groups = new int[8];
        int index = 0;
        for (String group : leftGroups) {
            int value = parseHextet(group);
            if (value < 0) {
                return null;
            }
            groups[index++] = value;
        }

        index += zeroGroups;
        for (String group : rightGroups) {
            int value = parseHextet(group);
            if (value < 0 || index >= groups.length) {
                return null;
            }
            groups[index++] = value;
        }

        if (index != groups.length) {
            return null;
        }

        return Integer.toHexString(groups[0]) + ':'
                + Integer.toHexString(groups[1]) + ':'
                + Integer.toHexString(groups[2]) + ':'
                + Integer.toHexString(groups[3]) + "::";
    }

    private static int parseHextet(String s) {
        if (s.isEmpty() || s.length() > 4) {
            return -1;
        }

        int result = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            int digit = Character.digit(c, 16);
            if (digit < 0) {
                return -1;
            }

            result = (result << 4) | digit;
        }

        return result;
    }
}
