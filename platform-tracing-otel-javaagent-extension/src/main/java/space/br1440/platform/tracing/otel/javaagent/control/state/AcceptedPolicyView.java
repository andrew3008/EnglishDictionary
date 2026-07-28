package space.br1440.platform.tracing.otel.javaagent.control.state;

import java.util.NavigableMap;

/**
 * Внутреннее read-only представление одного атомарно принятого runtime snapshot.
 *
 * <p>Тип является межпакетным контрактом только внутри Agent extension и не является SPI.
 */
public interface AcceptedPolicyView {

    String runtimeInstanceId();

    long acceptedSequence();

    boolean samplingEnabled();

    double samplingRatio();

    NavigableMap<String, Double> samplingRouteRatios();

    boolean validationEnabled();

    boolean validationStrict();

    boolean exporterEnabled();

    boolean traceControlHeadersEnabled();

    String diagnosticsLogLevel();
}
