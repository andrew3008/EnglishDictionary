package space.br1440.platform.tracing.autoconfigure.control.management;

import lombok.experimental.UtilityClass;

import java.util.Map;

@UtilityClass
public final class TracingPolicyManagementRequests {

    public record SetSamplingEnabledRequest(boolean enabled) {}

    public record SetSamplingRatioRequest(double ratio) {}

    public record ReplaceSamplingRouteRatiosRequest(Map<String, Double> routeRatios) {}

    public record SetValidationEnabledRequest(boolean enabled) {}

    public record SetValidationStrictRequest(boolean strict) {}

    public record SetExporterEnabledRequest(boolean enabled) {}

    public record SetTraceControlHeadersEnabledRequest(boolean enabled) {}

    public record SetDiagnosticsLogLevelRequest(String level) {}

}
