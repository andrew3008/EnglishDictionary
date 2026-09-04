package space.br1440.platform.logging.logbook.filter;

import org.jspecify.annotations.NullMarked;
import org.zalando.logbook.QueryFilter;
import space.br1440.platform.logging.masking.SensitiveDataMasker;

import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

@NullMarked
public class QuerySensitiveDataFilter implements QueryFilter {

    private final SensitiveDataMasker masker;

    public QuerySensitiveDataFilter(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    @Override
    public String filter(final String query) {
        if (query.isEmpty()) {
            return query;
        }

        StringBuilder result = new StringBuilder();
        int start = 0;
        while (start < query.length()) {
            int amp = query.indexOf('&', start);
            int end = (amp >= 0) ? amp : query.length();

            String param = query.substring(start, end);
            int eq = param.indexOf('=');
            String processed;
            if (eq < 0) {
                processed = param;
            } else {
                try {
                    String name = URLDecoder.decode(param.substring(0, eq), StandardCharsets.UTF_8);
                    String value = URLDecoder.decode(param.substring(eq + 1), StandardCharsets.UTF_8);

                    String masked = masker.maskValue(name, value);

                    processed = URLEncoder.encode(name, StandardCharsets.UTF_8) + "=" +
                            URLEncoder.encode(masked, StandardCharsets.UTF_8);
                } catch (Exception e) {
                    processed = param;
                }
            }

            if (!result.isEmpty()) {
                result.append('&');
            }
            result.append(processed);

            start = end + 1;
        }

        return result.toString();
    }

}
