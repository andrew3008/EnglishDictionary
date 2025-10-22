package space.br1440.platform.logging.logback;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;
import space.br1440.platform.logging.configuration.LoggingProperties;
import space.br1440.platform.logging.masking.SensitiveDataMasker;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class MdcSensitiveDataMaskerConverter extends ClassicConverter {

    private static SensitiveDataMasker masker;
    private static Set<String> excludedKeys;

    public static void setMasker(SensitiveDataMasker masker, LoggingProperties loggingProperties) {
        MdcSensitiveDataMaskerConverter.masker = masker;
        MdcSensitiveDataMaskerConverter.excludedKeys = loggingProperties.getMdcExcludedKeys();
    }

    @Override
    public String convert(ILoggingEvent event) {
        if (masker == null) {
            return "";
        }

        Map<String, String> mdc = event.getMDCPropertyMap();
        if (mdc == null || mdc.isEmpty()) {
            return "";
        }

        String result = mdc.entrySet().stream()
                .filter(e -> !excludedKeys.contains(e.getKey()))
                .sorted(Map.Entry.comparingByKey())
                .map(e -> e.getKey() + "=" + masker.maskValue(e.getKey(), e.getValue()))
                .collect(Collectors.joining(" "));

        return result.isEmpty() ? "" : " [" + result + "]";
    }

}
