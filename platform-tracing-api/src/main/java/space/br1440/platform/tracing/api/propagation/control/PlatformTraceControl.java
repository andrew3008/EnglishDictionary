package space.br1440.platform.tracing.api.propagation.control;

import space.br1440.platform.tracing.api.attributes.PlatformSamplingReasons;
import space.br1440.platform.tracing.api.propagation.RequestIdSupport;

/**
 * Извлечение параметров управления распределённой трассировкой из carrier (например, HTTP-заголовков) при входящем запросе.
 */
public record PlatformTraceControl(boolean forceTrace,
                                   boolean qaTrace,
                                   String requestId,
                                   String samplingReason,
                                   String rawForceTraceValue) {

    public static PlatformTraceControl fromHeaders(String traceOn, String qaTrace, String requestId) {
        boolean isForceTrace = "on".equalsIgnoreCase(traceOn);
        boolean isQaTrace = (qaTrace != null) && !qaTrace.isBlank();

        String reason = null;
        if (isForceTrace) {
            reason = PlatformSamplingReasons.FORCE_HEADER;
        } else if (isQaTrace) {
            reason = PlatformSamplingReasons.QA_TRACE;
        }

        String validRequestId = RequestIdSupport.sanitizeOrNull(requestId);
        return new PlatformTraceControl(isForceTrace, isQaTrace, validRequestId, reason, traceOn);
    }
}
