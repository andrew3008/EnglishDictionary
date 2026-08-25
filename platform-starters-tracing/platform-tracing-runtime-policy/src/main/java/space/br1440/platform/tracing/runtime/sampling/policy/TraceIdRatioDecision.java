package space.br1440.platform.tracing.runtime.sampling.policy;

import space.br1440.platform.common.util.StringUtils;

import lombok.experimental.UtilityClass;

@UtilityClass
final class TraceIdRatioDecision {

    private static final int TRACE_ID_LENGTH = 32;
    private static final int RANDOM_PART_OFFSET = 16;
    private static final int HEX_RADIX = 16;

    static boolean shouldSample(String traceId, double probability) {
        if (probability >= 1.0) {
            return true;
        }

        if (probability <= 0.0) {
            return false;
        }

        if (StringUtils.isNullOrEmpty(traceId) || (traceId.length() < TRACE_ID_LENGTH)) {
            return false;
        }

        long idUpperBound = (long) (probability * Long.MAX_VALUE);
        return Math.abs(traceIdRandomPart(traceId)) < idUpperBound;
    }

    private static long traceIdRandomPart(String traceId) {
        return Long.parseUnsignedLong(traceId.substring(RANDOM_PART_OFFSET), HEX_RADIX);
    }
}
