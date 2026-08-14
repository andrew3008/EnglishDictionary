package space.br1440.platform.tracing.autoconfigure.control.jmx.protocol;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

record PrivateControlSnapshot(String expectedRuntimeInstanceId,
                              long expectedAcceptedSequence,
                              String attemptId,
                              String sourceAuthority,
                              String applyTrigger,
                              String sourceRevision,
                              String candidateDigest,
                              boolean samplingEnabled,
                              double samplingRatio,
                              Map<String, Double> samplingRouteRatios,
                              boolean validationEnabled,
                              boolean validationStrict,
                              boolean exporterEnabled,
                              boolean traceControlHeadersEnabled,
                              String diagnosticsLogLevel) {

    PrivateControlSnapshot {
        samplingRouteRatios = Collections.unmodifiableMap(
                new LinkedHashMap<>(
                        Objects.requireNonNull(samplingRouteRatios,"samplingRouteRatios")
                )
        );
    }
}
