package space.br1440.platform.tracing.jmx.contract.tool;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

import lombok.experimental.UtilityClass;

@UtilityClass
final class ContractCanonicalizer {

    static String canonical(Object value) {
        StringBuilder result = new StringBuilder();
        appendCanonical(result, value);
        return result.toString();
    }

    static String sha256(String value) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8));

            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void appendCanonical(StringBuilder output, Object rawValue) {
        Object value = rawValue instanceof String string
                ? Normalizer.normalize(string, Normalizer.Form.NFC)
                : rawValue;

        if (value instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = ContractValues.map(rawMap, "canonical.map");
            List<String> keys = new ArrayList<>(map.keySet());
            keys.sort(ContractCanonicalizer::compareCodePoints);

            output.append("map{");
            for (String key : keys) {
                appendCanonical(output, key);
                appendCanonical(output, map.get(key));
            }
            output.append('}');
        } else if (value instanceof List<?> list) {
            output.append("list[");
            for (Object item : list) {
                appendCanonical(output, item);
            }
            output.append(']');
        } else if (value instanceof String string) {
            byte[] encoded = string.getBytes(StandardCharsets.UTF_8);
            output.append("string:").append(encoded.length).append(':').append(string).append(';');
        } else if (value instanceof Boolean bool) {
            output.append(bool ? "boolean:true;" : "boolean:false;");
        } else if (value instanceof Byte || value instanceof Short
                || value instanceof Integer || value instanceof Long
                || value instanceof java.math.BigInteger) {
            output.append("integer:").append(value).append(';');
        } else if (value instanceof Number number) {
            double numberValue = number.doubleValue();
            if (!Double.isFinite(numberValue)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_NUMBER_INVALID", "Canonical schema numbers must be finite");
            }
            output.append("double:").append(Double.toHexString(numberValue)).append(';');
        } else if (value == null) {
            output.append("null;");
        } else {
            throw new ContractValidationException(
                    "JMX_SCHEMA_FIELD_TYPE_MISMATCH",
                    "Canonical schema contains unsupported value type: " + value.getClass().getName()
            );
        }
    }

    private static int compareCodePoints(String left, String right) {
        int leftOffset = 0;
        int rightOffset = 0;
        while (leftOffset < left.length() && rightOffset < right.length()) {
            int leftCodePoint = left.codePointAt(leftOffset);
            int rightCodePoint = right.codePointAt(rightOffset);
            int comparison = Integer.compare(leftCodePoint, rightCodePoint);
            if (comparison != 0) {
                return comparison;
            }

            leftOffset += Character.charCount(leftCodePoint);
            rightOffset += Character.charCount(rightCodePoint);
        }

        return Integer.compare(left.length() - leftOffset, right.length() - rightOffset);
    }
}
