package space.br1440.platform.tracing.test.harness;

import io.opentelemetry.api.trace.SpanKind;
import io.opentelemetry.api.trace.StatusCode;

import space.br1440.platform.common.util.StringUtils;

public record EnrichmentDecisionCase(String caseId,
                                     SpanKind spanKind,
                                     StatusCode statusCode,
                                     String presetPlatformType,
                                     String dbSystemAttribute,
                                     String peerService,
                                     String expectedPlatformType,
                                     String expectedPlatformResult,
                                     String expectedRemoteService) {

    public EnrichmentDecisionCase {
        if (StringUtils.isNullOrBlank(caseId)) {
            throw new IllegalArgumentException("caseId required");
        }

        spanKind = (spanKind == null) ? SpanKind.INTERNAL : spanKind;
        statusCode = (statusCode == null) ? StatusCode.OK : statusCode;
    }
}
