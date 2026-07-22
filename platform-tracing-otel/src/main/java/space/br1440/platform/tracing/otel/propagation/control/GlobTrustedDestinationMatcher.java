package space.br1440.platform.tracing.otel.propagation.control;

import space.br1440.platform.tracing.api.propagation.control.TrustedDestinationMatcher;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

final class GlobTrustedDestinationMatcher implements TrustedDestinationMatcher {

    private static final Pattern IPV4_LITERAL = Pattern.compile("^\\d{1,3}(?:\\.\\d{1,3}){3}$");

    private final List<Pattern> patterns;
    private final boolean hostMode;
    private final boolean allowIpLiterals;

    GlobTrustedDestinationMatcher(List<String> globs, boolean hostMode, boolean allowIpLiterals) {
        this.hostMode = hostMode;
        this.allowIpLiterals = allowIpLiterals;
        this.patterns = (globs == null) ? List.of() : globs.stream()
                .filter(p -> (p != null) && !p.isBlank())
                .map(p -> compileGlob(p, hostMode))
                .toList();
    }

    @Override
    public boolean isTrusted(String destination) {
        String d = hostMode ? canonicalizeHost(destination) : canonicalizeTopic(destination);
        if (d == null) {
            return false;
        }

        if (hostMode && !allowIpLiterals && IPV4_LITERAL.matcher(d).matches()) {
            return false;
        }

        for (Pattern pattern : patterns) {
            if (pattern.matcher(d).matches()) {
                return true;
            }
        }

        return false;
    }

    private static String canonicalizeHost(String host) {
        if (host == null) {
            return null;
        }

        String h = host.trim();
        if (h.isEmpty()) {
            return null;
        }

        if (h.endsWith(".")) {
            h = h.substring(0, h.length() - 1);
        }

        h = h.toLowerCase(Locale.ROOT);

        for (int i = 0; i < h.length(); i++) {
            char c = h.charAt(i);
            boolean ok = (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '-' || c == '.';
            if (!ok) {
                return null;
            }
        }

        return h;
    }

    private static String canonicalizeTopic(String topic) {
        if (topic == null) {
            return null;
        }

        String t = topic.trim();
        return t.isEmpty() ? null : t;
    }

    private static Pattern compileGlob(String glob, boolean hostMode) {
        String g = glob.trim();
        if (hostMode) {
            g = g.toLowerCase(Locale.ROOT);
            if (g.endsWith(".")) {
                g = g.substring(0, g.length() - 1);
            }
        }

        StringBuilder sb = new StringBuilder("^");
        int i = 0;
        while (i < g.length()) {
            char c = g.charAt(i);
            if (c == '*') {
                if (hostMode) {
                    if (i + 1 < g.length() && g.charAt(i + 1) == '*') {
                        sb.append("[a-z0-9-]+(?:\\.[a-z0-9-]+)*");
                        i += 2;
                    } else {
                        sb.append("[a-z0-9-]+");
                        i += 1;
                    }
                } else {
                    sb.append(".*");
                    i += 1;
                }
            } else if (c == '.') {
                sb.append("\\.");
                i++;
            } else {
                if ("\\^$+?()[]{}|".indexOf(c) >= 0) {
                    sb.append('\\');
                }
                sb.append(c);
                i++;
            }
        }
        sb.append('$');

        int flags = hostMode ? Pattern.CASE_INSENSITIVE : 0;
        return Pattern.compile(sb.toString(), flags);
    }
}
