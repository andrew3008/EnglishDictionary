package space.br1440.platform.errorhandling.core.util.http;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.experimental.UtilityClass;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.model.ErrorEntry;
import space.br1440.platform.errorhandling.core.util.StringUtils;

import java.util.Optional;

/**
 * Парсер тела upstream-ответа для извлечения платформенного {@code errorCode}.
 *
 * <p>Используется в {@code FeignExceptionStrategy} и {@code WebFluxExceptionStrategy}
 * для propagation структурированного error code из upstream-сервиса,
 * когда upstream возвращает тело в формате {@code ErrorEntryDTO}.
 *
 * <h2>Формат upstream body</h2>
 * <p>Ожидается JSON-объект, содержащий как минимум поле {@code errorCode}:
 * <pre>{@code {"httpCode": 404, "errorCode": "ORD-0042", "message": "...", ...}}</pre>
 *
 * @see <a href="https://cwe.mitre.org/data/definitions/209.html">CWE-209</a>
 */
@UtilityClass
public final class UpstreamErrorBodyParser {

    private static final ObjectMapper LENIENT_MAPPER = new ObjectMapper()
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);

    static final int MAX_BODY_PARSE_SIZE = 8192;
    static final int MAX_FIELD_LENGTH = 128;

    @NonNull
    public static Optional<UpstreamErrorInfo> parse(@Nullable String body, boolean includeReason) {
        if (!StringUtils.hasText(body) || body.length() > MAX_BODY_PARSE_SIZE) {
            return Optional.empty();
        }

        try {
            JsonNode root = LENIENT_MAPPER.readTree(body);
            if (!root.isObject()) {
                return Optional.empty();
            }

            String errorCode = extractString(root, "errorCode");
            if (!StringUtils.hasText(errorCode)) {
                return Optional.empty();
            }

            String reason = includeReason ? extractString(root, "reason") : null;
            String domain = extractString(root, "domain");

            return Optional.of(new UpstreamErrorInfo(errorCode, reason, domain));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    @Nullable
    private static String extractString(@NonNull JsonNode node, @NonNull String fieldName) {
        JsonNode field = node.get(fieldName);
        if (field != null && field.isTextual()) {
            String value = field.asText();
            if (StringUtils.hasText(value)) {
                return StringUtils.truncate(value, MAX_FIELD_LENGTH);
            }
        }

        return null;
    }

    @NonNull
    public static ErrorEntry applyUpstreamInfo(@NonNull ErrorEntry entry,
                                               @NonNull UpstreamErrorInfo info) {
        ErrorEntry.ErrorEntryBuilder builder = entry.toBuilder()
                .errorCode(info.errorCode());

        if (info.reason() != null) {
            builder.reason(info.reason());
        }

        if (info.domain() != null) {
            builder.domain(info.domain());
        }

        return builder.build();
    }

    public record UpstreamErrorInfo(@NonNull String errorCode,
                                    @Nullable String reason,
                                    @Nullable String domain) {
    }
}
