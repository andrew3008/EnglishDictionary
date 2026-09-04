package space.br1440.platform.logging.logbook.filter;

import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.zalando.logbook.BodyFilter;
import org.zalando.logbook.ContentType;
import space.br1440.platform.logging.masking.SensitiveDataMasker;

@NullMarked
public class BodySensitiveDataFilter implements BodyFilter {

    private final SensitiveDataMasker masker;

    public BodySensitiveDataFilter(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    @Override
    public String filter(@Nullable String contentType, String body) {
        if (body.isEmpty()) {
            return body;
        }

        if (ContentType.isJsonMediaType(contentType)) {
            return masker.maskMessage(body);
        }

        return body;
    }

}
