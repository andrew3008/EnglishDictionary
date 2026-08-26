package space.br1440.platform.tracing.jmx.contract.tool;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class PolicyDigestEncoder {

    private static final List<String> FIELD_ORDER = List.of(
            "samplingEnabled",
            "samplingRatio",
            "samplingRouteRatios",
            "validationEnabled",
            "validationStrict",
            "exporterEnabled",
            "traceControlHeadersEnabled",
            "diagnosticsLogLevel"
    );

    private PolicyDigestEncoder() {
    }

    static String digest(ContractDocument contract, Map<String, Object> policy) {
        validatePolicyKeys(policy);
        Map<String, Object> digest = contract.section("policyDigest");
        Map<String, Object> wireGraph = contract.section("wireGraph");
        // Лимиты маршрутов берутся из того же манифеста, что и в генерируемом runtime-классе:
        // без этого golden-векторы не поймали бы нарушение лимита на сборке.
        int maxRouteEntries = integer(wireGraph.get("maxRouteEntries"), "wireGraph.maxRouteEntries");
        int maxRoutePrefixUtf8Bytes = integer(
                wireGraph.get("maxRoutePrefixUtf8Bytes"), "wireGraph.maxRoutePrefixUtf8Bytes");
        String algorithm = string(digest.get("algorithmId"), "policyDigest.algorithmId");
        int encodingVersion = integer(digest.get("encodingVersion"), "policyDigest.encodingVersion");
        String domainSeparator = string(digest.get("domainSeparator"), "policyDigest.domainSeparator");
        boolean normalizeNegativeZero = bool(
                digest.get("normalizeNegativeZero"), "policyDigest.normalizeNegativeZero");
        Set<String> logLevels = Set.copyOf(strings(digest.get("validLogLevels"), "policyDigest.validLogLevels"));

        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(domainSeparator.getBytes(StandardCharsets.UTF_8));
                output.writeByte(encodingVersion);
                writeBoolean(output, 1, bool(policy.get("samplingEnabled"), "policy.samplingEnabled"));
                writeDouble(
                        output,
                        2,
                        number(policy.get("samplingRatio"), "policy.samplingRatio"),
                        normalizeNegativeZero
                );
                writeRouteRatios(
                        output,
                        3,
                        routeRatios(policy.get("samplingRouteRatios")),
                        normalizeNegativeZero,
                        maxRouteEntries,
                        maxRoutePrefixUtf8Bytes
                );
                writeBoolean(output, 4, bool(policy.get("validationEnabled"), "policy.validationEnabled"));
                writeBoolean(output, 5, bool(policy.get("validationStrict"), "policy.validationStrict"));
                writeBoolean(output, 6, bool(policy.get("exporterEnabled"), "policy.exporterEnabled"));
                writeBoolean(
                        output,
                        7,
                        bool(policy.get("traceControlHeadersEnabled"), "policy.traceControlHeadersEnabled")
                );
                writeAsciiToken(
                        output,
                        8,
                        string(policy.get("diagnosticsLogLevel"), "policy.diagnosticsLogLevel"),
                        logLevels
                );
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode tracing policy digest", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Policy digest algorithm is unavailable: " + algorithm, exception);
        }
    }

    private static void validatePolicyKeys(Map<String, Object> policy) {
        if (!policy.keySet().equals(Set.copyOf(FIELD_ORDER))) {
            Set<String> unknown = new java.util.HashSet<>(policy.keySet());
            unknown.removeAll(FIELD_ORDER);
            if (!unknown.isEmpty()) {
                throw new ContractValidationException(
                        "JMX_POLICY_UNKNOWN_FIELD", "Policy has unknown fields: " + unknown);
            }
            Set<String> missing = new java.util.HashSet<>(FIELD_ORDER);
            missing.removeAll(policy.keySet());
            throw new ContractValidationException(
                    "JMX_POLICY_REQUIRED_FIELD_MISSING", "Policy is missing fields: " + missing);
        }
    }

    private static void writeBoolean(DataOutputStream output, int fieldId, boolean value) throws IOException {
        output.writeByte(fieldId);
        output.writeByte(value ? 1 : 0);
    }

    private static void writeDouble(
            DataOutputStream output,
            int fieldId,
            double value,
            boolean normalizeNegativeZero
    ) throws IOException {
        requireRatio(value, "policy ratio");
        output.writeByte(fieldId);
        output.writeLong(Double.doubleToLongBits(normalize(value, normalizeNegativeZero)));
    }

    private static void writeRouteRatios(
            DataOutputStream output,
            int fieldId,
            Map<String, Double> ratios,
            boolean normalizeNegativeZero,
            int maxRouteEntries,
            int maxRoutePrefixUtf8Bytes
    ) throws IOException {
        if (ratios.size() > maxRouteEntries) {
            throw new ContractValidationException(
                    "JMX_POLICY_ROUTE_LIMIT_EXCEEDED", "Too many sampling route ratios: " + ratios.size());
        }
        output.writeByte(fieldId);
        List<RouteRatio> encoded = new ArrayList<>(ratios.size());
        for (Map.Entry<String, Double> entry : ratios.entrySet()) {
            if (entry.getKey() == null || entry.getKey().isEmpty()) {
                throw new ContractValidationException(
                        "JMX_POLICY_INVALID_ROUTE_PREFIX", "Route prefix must not be empty");
            }
            byte[] encodedPrefix = entry.getKey().getBytes(StandardCharsets.UTF_8);
            if (encodedPrefix.length > maxRoutePrefixUtf8Bytes) {
                throw new ContractValidationException(
                        "JMX_POLICY_INVALID_ROUTE_PREFIX",
                        "Route prefix exceeds the UTF-8 length limit: " + encodedPrefix.length);
            }
            requireRatio(entry.getValue(), "route ratio");
            encoded.add(new RouteRatio(
                    encodedPrefix,
                    normalize(entry.getValue(), normalizeNegativeZero)
            ));
        }
        encoded.sort(Comparator.comparing(RouteRatio::routePrefix, PolicyDigestEncoder::compareUnsigned));

        output.writeInt(encoded.size());
        for (RouteRatio routeRatio : encoded) {
            output.writeInt(routeRatio.routePrefix().length);
            output.write(routeRatio.routePrefix());
            output.writeLong(Double.doubleToLongBits(routeRatio.ratio()));
        }
    }

    private static void writeAsciiToken(
            DataOutputStream output,
            int fieldId,
            String token,
            Set<String> allowed
    ) throws IOException {
        if (!allowed.contains(token)) {
            throw new ContractValidationException(
                    "JMX_POLICY_INVALID_LOG_LEVEL", "Unsupported diagnostics log level: " + token);
        }
        if (!StandardCharsets.US_ASCII.newEncoder().canEncode(token)) {
            throw new ContractValidationException(
                    "JMX_POLICY_INVALID_LOG_LEVEL", "Diagnostics log level must be an ASCII token");
        }
        output.writeByte(fieldId);
        byte[] encoded = token.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(encoded.length);
        output.write(encoded);
    }

    private static Map<String, Double> routeRatios(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new ContractValidationException(
                    "JMX_POLICY_FIELD_TYPE_MISMATCH", "samplingRouteRatios must be a map");
        }
        Map<String, Double> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key) || !(entry.getValue() instanceof Number number)) {
                throw new ContractValidationException(
                        "JMX_POLICY_FIELD_TYPE_MISMATCH", "samplingRouteRatios must map strings to numbers");
            }
            result.put(key, number.doubleValue());
        }
        return result;
    }

    private static void requireRatio(double value, String label) {
        if (!Double.isFinite(value) || value < 0.0d || value > 1.0d) {
            throw new ContractValidationException(
                    "JMX_POLICY_RATIO_OUT_OF_RANGE", label + " must be finite and between 0.0 and 1.0");
        }
    }

    private static double normalize(double value, boolean normalizeNegativeZero) {
        return normalizeNegativeZero && value == 0.0d ? 0.0d : value;
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int commonLength = Math.min(left.length, right.length);
        for (int index = 0; index < commonLength; index++) {
            int comparison = Integer.compare(Byte.toUnsignedInt(left[index]), Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) {
            throw new ContractValidationException(
                    "JMX_POLICY_FIELD_TYPE_MISMATCH", path + " must be a boolean");
        }
        return result;
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number) || number.intValue() != number.doubleValue()) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_FIELD_TYPE_MISMATCH", path + " must be an integer");
        }
        return number.intValue();
    }

    private static double number(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw new ContractValidationException(
                    "JMX_POLICY_FIELD_TYPE_MISMATCH", path + " must be a number");
        }
        return number.doubleValue();
    }

    private static String string(Object value, String path) {
        if (!(value instanceof String result)) {
            throw new ContractValidationException(
                    "JMX_POLICY_FIELD_TYPE_MISMATCH", path + " must be a string");
        }
        return result;
    }

    private static List<String> strings(Object value, String path) {
        if (!(value instanceof List<?> list)) {
            throw new ContractValidationException(
                    "JMX_SCHEMA_FIELD_TYPE_MISMATCH", path + " must be a list");
        }
        List<String> result = new ArrayList<>(list.size());
        for (Object item : list) {
            if (!(item instanceof String string)) {
                throw new ContractValidationException(
                        "JMX_SCHEMA_FIELD_TYPE_MISMATCH", path + " must contain strings");
            }
            result.add(string);
        }
        return result;
    }

    private record RouteRatio(byte[] routePrefix, double ratio) {
    }
}
