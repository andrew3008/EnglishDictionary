package space.br1440.platform.tracing.jmx.contract.tool;

import java.util.List;

import lombok.experimental.UtilityClass;

@UtilityClass
public final class ContractSchema {

    public record PolicyDigestField(int id, String name, String type) {
    }

    public static final List<PolicyDigestField> POLICY_DIGEST_FIELDS = List.of(
            new PolicyDigestField(1, "samplingEnabled", "boolean"),
            new PolicyDigestField(2, "samplingRatio", "double"),
            new PolicyDigestField(3, "samplingRouteRatios", "sortedUtf8DoubleMap"),
            new PolicyDigestField(4, "validationLevel", "asciiToken"),
            new PolicyDigestField(5, "exporterEnabled", "boolean"),
            new PolicyDigestField(6, "traceControlHeadersEnabled", "boolean"),
            new PolicyDigestField(7, "diagnosticsLogLevel", "asciiToken")
    );
}
