package space.br1440.platform.datasource.configuration.support;

import lombok.experimental.UtilityClass;

import java.util.LinkedHashMap;
import java.util.Map;

@UtilityClass
public final class ConnectParametersMerger {

    public static Map<String, String> merge(Map<String, String> base, Map<String, String> extra) {
        Map<String, String> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }

        if (extra != null) {
            merged.putAll(extra);
        }

        return merged;
    }
}
