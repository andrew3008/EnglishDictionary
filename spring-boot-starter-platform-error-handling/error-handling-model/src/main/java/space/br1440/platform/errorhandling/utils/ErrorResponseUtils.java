package space.br1440.platform.errorhandling.utils;

import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.regex.Pattern;

import static org.apache.commons.lang3.StringUtils.EMPTY;

@Slf4j
@UtilityClass
public final class ErrorResponseUtils {

    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\$\\{[^}]+}");

    /**
     * Заменяет placeholder'ы {@code ${key}} в шаблоне сообщения значениями из {@code extraMap}.
     */
    public static String formatMessage(String messageTemplate, Map<String, String> extraMap) {
        if (messageTemplate == null) {
            return "";
        }

        if (extraMap == null || extraMap.isEmpty()) {
            return messageTemplate;
        }

        String message = messageTemplate;
        for (Map.Entry<String, String> entry : extraMap.entrySet()) {
            message = message.replace("${" + entry.getKey() + "}", entry.getValue() == null ? EMPTY : entry.getValue());
        }

        if (UNRESOLVED_PLACEHOLDER.matcher(message).find()) {
            log.warn("Unresolved placeholders in error message: {}", message);
        }

        return message;
    }
}
