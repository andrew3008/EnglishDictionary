package space.br1440.platform.datasource.postgres.internal.bootstrap;

import lombok.experimental.UtilityClass;
import org.springframework.util.StringUtils;
import space.br1440.platform.datasource.postgres.api.exception.PostgresClusterConfigurationException;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@UtilityClass
public final class PgConnectionPropertiesParser {

    static final Set<String> FORBIDDEN_KEYS = Set.of("user", "password");

    static final String MSG_MALFORMED = "Malformed PG_CONNECTION_PROPERTIES: ";

    static final String ACTION_MALFORMED = """
            Expected key=value pairs separated by ';', e.g.:
              PG_CONNECTION_PROPERTIES=applicationName=orders;sslmode=require""".stripIndent();

    static final String MSG_FORBIDDEN = "Forbidden key in PG_CONNECTION_PROPERTIES: ";

    static final String ACTION_FORBIDDEN = """
            Keys 'user'/'password' are not allowed in PG_CONNECTION_PROPERTIES.
            Supply credentials via PG_USERNAME / PG_PASSWORD environment variables instead.""".stripIndent();

    public static Map<String, String> parse(String raw) {
        Map<String, String> result = new LinkedHashMap<>();
        if (!StringUtils.hasText(raw)) {
            return result;
        }

        for (String pair : raw.split(";")) {
            String trimmedPair = pair.strip();
            if (trimmedPair.isEmpty()) {
                continue;
            }

            int eq = trimmedPair.indexOf('=');
            if (eq <= 0 || eq == trimmedPair.length() - 1) {
                throw new PostgresClusterConfigurationException(MSG_MALFORMED + "invalid pair '" + trimmedPair + "'", ACTION_MALFORMED);
            }

            String key = trimmedPair.substring(0, eq).strip();
            String value = trimmedPair.substring(eq + 1).strip();
            if (key.isEmpty()) {
                throw new PostgresClusterConfigurationException(MSG_MALFORMED + "blank key in '" + trimmedPair + "'", ACTION_MALFORMED);
            }

            if (FORBIDDEN_KEYS.contains(key.toLowerCase(Locale.ROOT))) {
                throw new PostgresClusterConfigurationException(MSG_FORBIDDEN + key, ACTION_FORBIDDEN);
            }

            result.put(key, value);
        }

        return result;
    }
}
