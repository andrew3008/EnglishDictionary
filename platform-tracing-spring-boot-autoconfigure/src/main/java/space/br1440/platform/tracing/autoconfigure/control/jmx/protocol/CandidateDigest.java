package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import space.br1440.platform.tracing.autoconfigure.control.policy.CompleteTracingPolicySnapshot;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * Spring-side mirror of the Agent {@code EffectiveTracingPolicyDigest.sha256(...)} algorithm.
 * <p>
 * Wire contract requirement: {@code applySnapshot} REQUEST.candidateDigest MUST equal the
 * Agent-computed digest of the same eight-leaf policy, or the Agent rejects the request with
 * {@code INVALID_VALUE}. The Agent-side algorithm
 * ({@code otel.javaagent.control.state.EffectiveTracingPolicyDigest}) is package-private and
 * intentionally not exposed as production API — this class is a byte-for-byte port, verified
 * against the real Agent implementation by
 * {@code CandidateDigestAgentParityTest} (cross-module, test-scope only).
 * <p>
 * Any change to the Agent algorithm MUST be mirrored here and re-verified by that test.
 */
final class CandidateDigest {

    private static final byte[] DOMAIN_SEPARATOR =
            "platform-tracing-policy-v1".getBytes(StandardCharsets.UTF_8);
    private static final int SAMPLING_ENABLED_FIELD = 1;
    private static final int SAMPLING_RATIO_FIELD = 2;
    private static final int SAMPLING_ROUTE_RATIOS_FIELD = 3;
    private static final int VALIDATION_ENABLED_FIELD = 4;
    private static final int VALIDATION_STRICT_FIELD = 5;
    private static final int EXPORTER_ENABLED_FIELD = 6;
    private static final int TRACE_CONTROL_HEADERS_ENABLED_FIELD = 7;
    private static final int DIAGNOSTICS_LOG_LEVEL_FIELD = 8;

    private CandidateDigest() {
    }

    static String sha256(CompleteTracingPolicySnapshot policy) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.write(DOMAIN_SEPARATOR);
                output.writeByte(0);
                writeBoolean(output, SAMPLING_ENABLED_FIELD, policy.samplingEnabled());
                writeDouble(output, SAMPLING_RATIO_FIELD, policy.samplingRatio());
                writeRouteRatios(output, SAMPLING_ROUTE_RATIOS_FIELD, policy.samplingRouteRatios());
                writeBoolean(output, VALIDATION_ENABLED_FIELD, policy.validationEnabled());
                writeBoolean(output, VALIDATION_STRICT_FIELD, policy.validationStrict());
                writeBoolean(output, EXPORTER_ENABLED_FIELD, policy.exporterEnabled());
                writeBoolean(output, TRACE_CONTROL_HEADERS_ENABLED_FIELD, policy.traceControlHeadersEnabled());
                writeLogLevel(output, DIAGNOSTICS_LOG_LEVEL_FIELD, policy.diagnosticsLogLevel());
            }
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(bytes.toByteArray()));
        } catch (IOException exception) {
            throw new IllegalStateException("Cannot encode tracing policy digest", exception);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static void writeBoolean(DataOutputStream output, int fieldId, boolean value) throws IOException {
        output.writeByte(fieldId);
        output.writeByte(value ? 1 : 0);
    }

    private static void writeDouble(DataOutputStream output, int fieldId, double value) throws IOException {
        output.writeByte(fieldId);
        output.writeLong(Double.doubleToLongBits(value));
    }

    private static void writeRouteRatios(
            DataOutputStream output,
            int fieldId,
            Map<String, Double> routeRatios) throws IOException {
        output.writeByte(fieldId);
        List<EncodedRouteRatio> entries = new ArrayList<>(routeRatios.size());
        for (Map.Entry<String, Double> entry : routeRatios.entrySet()) {
            entries.add(new EncodedRouteRatio(
                    entry.getKey().getBytes(StandardCharsets.UTF_8),
                    entry.getValue()));
        }
        entries.sort(Comparator.comparing(EncodedRouteRatio::routePrefix, CandidateDigest::compareUnsigned));

        output.writeInt(entries.size());
        for (EncodedRouteRatio entry : entries) {
            output.writeInt(entry.routePrefix().length);
            output.write(entry.routePrefix());
            output.writeLong(Double.doubleToLongBits(entry.ratio()));
        }
    }

    private static void writeLogLevel(DataOutputStream output, int fieldId, String logLevel) throws IOException {
        output.writeByte(fieldId);
        byte[] token = logLevel.getBytes(StandardCharsets.US_ASCII);
        output.writeInt(token.length);
        output.write(token);
    }

    private static int compareUnsigned(byte[] left, byte[] right) {
        int commonLength = Math.min(left.length, right.length);
        for (int index = 0; index < commonLength; index++) {
            int comparison = Integer.compare(
                    Byte.toUnsignedInt(left[index]),
                    Byte.toUnsignedInt(right[index]));
            if (comparison != 0) {
                return comparison;
            }
        }
        return Integer.compare(left.length, right.length);
    }

    private record EncodedRouteRatio(byte[] routePrefix, double ratio) {
    }
}
