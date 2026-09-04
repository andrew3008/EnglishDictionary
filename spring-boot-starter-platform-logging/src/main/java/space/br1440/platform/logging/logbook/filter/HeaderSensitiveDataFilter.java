package space.br1440.platform.logging.logbook.filter;

import org.jspecify.annotations.NullMarked;
import org.zalando.logbook.HeaderFilter;
import org.zalando.logbook.HttpHeaders;
import space.br1440.platform.logging.masking.SensitiveDataMasker;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@NullMarked
public class HeaderSensitiveDataFilter implements HeaderFilter {

    private final SensitiveDataMasker masker;

    public HeaderSensitiveDataFilter(SensitiveDataMasker masker) {
        this.masker = masker;
    }

    @Override
    public HttpHeaders filter(final HttpHeaders headers) {
        Map<String, List<String>> maskedHeaderValues = headers.entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(value -> masker.maskValue(e.getKey(), value))
                                .collect(Collectors.toList())
                ));

        return HttpHeaders.of(maskedHeaderValues);
    }

}
