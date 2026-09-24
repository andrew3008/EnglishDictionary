package space.br1440.platform.tracing.otel.javaagent.scrubbing.engine;

import lombok.experimental.UtilityClass;

import java.util.Locale;

@UtilityClass
public final class ScrubbingKeyNormalizer {

    public static String normalize(String key) {
        if (key == null) {
            return "";
        }

        return key.toLowerCase(Locale.ROOT).trim();
    }
}
