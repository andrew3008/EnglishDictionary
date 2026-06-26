package space.br1440.platform.errorhandling.core.strategy.top.mapping;

import lombok.experimental.UtilityClass;
import org.springframework.http.MediaType;
import org.springframework.lang.NonNull;
import org.springframework.lang.Nullable;
import space.br1440.platform.errorhandling.core.util.sanitize.ErrorMessageSanitizer;

import java.util.List;

@UtilityClass
final class MvcMappingHelpers {

    @NonNull
    static String resolveTypeName(@Nullable Class<?> type) {
        return (type != null) ? type.getSimpleName() : "unknown";
    }

    @NonNull
    static String sanitizeMethodList(@Nullable String[] values) {
        if (values == null || values.length == 0) {
            return "none";
        }

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                sb.append(", ");
            }

            sb.append(ErrorMessageSanitizer.sanitizeHttpMethod(values[i]));
        }

        return sb.toString();
    }

    @NonNull
    static String mediaTypesToString(@NonNull List<MediaType> mediaTypes) {
        return MediaType.toString(mediaTypes);
    }
}
