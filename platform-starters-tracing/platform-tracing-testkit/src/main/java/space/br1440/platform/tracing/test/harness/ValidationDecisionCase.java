package space.br1440.platform.tracing.test.harness;

import space.br1440.platform.common.util.StringUtils;

public record ValidationDecisionCase(String caseId,
                                     String validationLevel,
                                     boolean hasPlatformType,
                                     boolean hasPlatformResult,
                                     boolean expectExport,
                                     boolean expectMissingAttribute,
                                     Class<? extends Throwable> expectedThrowable) {

    public ValidationDecisionCase {
        if (StringUtils.isNullOrBlank(caseId)) {
            throw new IllegalArgumentException("caseId required");
        }

        if (StringUtils.isNullOrBlank(validationLevel)) {
            throw new IllegalArgumentException("validationLevel required");
        }
    }
}
