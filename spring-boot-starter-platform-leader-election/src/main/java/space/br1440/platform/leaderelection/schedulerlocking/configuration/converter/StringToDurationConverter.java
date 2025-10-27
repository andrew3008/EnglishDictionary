package space.br1440.platform.leaderelection.schedulerlocking.configuration.converter;

import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

import java.time.Duration;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Slf4j
public class StringToDurationConverter {

    private static final Pattern ISO8601 = Pattern.compile("^[+-]?P.*$");
    private static final Pattern SIMPLE = Pattern.compile("^([+-]?\\d+)([a-zA-Z]{0,2})$");
    private static final Map<String, ChronoUnit> UNITS = Map.of(
            "us", ChronoUnit.MICROS,
            "ns", ChronoUnit.NANOS,
            "ms", ChronoUnit.MILLIS,
            "s", ChronoUnit.SECONDS,
            "m", ChronoUnit.MINUTES,
            "h", ChronoUnit.HOURS,
            "d", ChronoUnit.DAYS,
            "", ChronoUnit.MILLIS
    );

    public static Duration convertWithDefault(String source, Duration defaultValue) {
        if (!StringUtils.hasText(source)) {
            return defaultValue;
        }

        try {
            return convert(source);
        } catch (Exception e) {
            log.warn("Invalid duration format '{}', using default value: {}", source, defaultValue, e);
            return defaultValue;
        }
    }

    public static Duration convert(String source) {
        if (!StringUtils.hasText(source)) {
            throw new IllegalArgumentException("Duration string cannot be null or empty");
        }

        try {
            if (ISO8601.matcher(source).matches()) {
                return Duration.parse(source);
            }

            Matcher matcher = SIMPLE.matcher(source);
            if (!matcher.matches()) {
                throw new IllegalArgumentException("'" + source + "' is not a valid duration format");
            }

            long amount = Long.parseLong(matcher.group(1));
            ChronoUnit unit = getUnit(matcher.group(2));
            return Duration.of(amount, unit);
        } catch (Exception ex) {
            throw new IllegalArgumentException("'" + source + "' is not a valid duration", ex);
        }
    }

    private static ChronoUnit getUnit(String value) {
        ChronoUnit unit = UNITS.get(value.toLowerCase());
        if (unit == null) {
            throw new IllegalArgumentException("Unknown time unit '" + value + "'. Supported units: " + UNITS.keySet());
        }
        return unit;
    }

}
