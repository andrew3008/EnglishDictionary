package space.br1440.platform.logging.masking;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.common.util.StringUtils;
import lombok.RequiredArgsConstructor;
import space.br1440.platform.logging.masking.model.ArrayMaskRule;
import space.br1440.platform.logging.masking.model.ScalarMaskRule;

import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RequiredArgsConstructor
public class PlatformSensitiveDataMasker implements SensitiveDataMasker {

    private final MaskRules rules;

    @Override
    public String maskValue(String name, String value) {
        if (name == null || value == null) {
            return value;
        }

        for (ScalarMaskRule rule : rules.getScalarRules()) {
            if (rule.applyToKeys().contains(name)) {
                return rule.mask();
            }
        }

        return value;
    }

    @Override
    public String maskMessage(String message) {
        if (StringUtils.isEmpty(message)) {
            return message;
        }

        try {
            ObjectMapper objectMapper = new ObjectMapper();
            objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

            Object json = objectMapper.readValue(message, Object.class);
            Object maskedJson = maskJsonObject(json);

            return objectMapper.writeValueAsString(maskedJson);
        } catch (IOException e) {
            return message;
        }
    }

    private Object maskJsonObject(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> maskedMap = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                String key = entry.getKey().toString();
                Object val = entry.getValue();

                if (val == null) {
                    maskedMap.put(key, null);
                    continue;
                }

                if (val instanceof List<?> list) {
                    maskedMap.put(key, maskJsonArray(key, list));
                } else if (isJsonPrimitiveValue(val)) {
                    maskedMap.put(key, maskScalarValue(key, val.toString()));
                } else {
                    maskedMap.put(key, maskJsonObject(val));
                }
            }
            return maskedMap;
        } else if (value instanceof List<?> list) {
            List<Object> maskedList = new ArrayList<>();
            for (Object item : list) {
                maskedList.add(maskJsonObject(item));
            }
            return maskedList;
        } else {
            return value;
        }
    }

    private List<Object> maskJsonArray(String key, List<?> list) {
        for (ArrayMaskRule rule : rules.getArrayRules()) {
            if (rule.applyToKeys().contains(key)) {
                List<Object> maskedList = new ArrayList<>();
                for (Object item : list) {
                    maskedList.add(item == null ? null : rule.maskItem());
                }
                return maskedList;
            }
        }

        List<Object> maskedList = new ArrayList<>();
        for (Object item : list) {
            if (item == null) {
                maskedList.add(null);
            } else if (isJsonPrimitiveValue(item)) {
                maskedList.add(maskScalarValue(key, item.toString()));
            } else {
                maskedList.add(maskJsonObject(item));
            }
        }
        return maskedList;
    }

    private String maskScalarValue(String key, String value) {
        for (ScalarMaskRule rule : rules.getScalarRules()) {
            if (rule.applyToKeys().contains(key)) {
                return rule.mask();
            }
        }
        return value;
    }

    private boolean isJsonPrimitiveValue(Object value) {
        return value instanceof String ||
                value instanceof Number ||
                value instanceof Boolean;
    }

}
