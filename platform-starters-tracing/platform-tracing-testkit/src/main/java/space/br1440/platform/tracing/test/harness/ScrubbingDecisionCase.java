package space.br1440.platform.tracing.test.harness;

import java.util.List;

import space.br1440.platform.common.util.StringUtils;

public record ScrubbingDecisionCase(String caseId,
                                    String inputKey,
                                    String inputValue,
                                    List<String> ruleNames,
                                    String hmacKey,
                                    String expectedStringValue,
                                    boolean valuePreservedExactly,
                                    String expectedFailureMode) {

    public ScrubbingDecisionCase {
        if (StringUtils.isNullOrBlank(caseId)) {
            throw new IllegalArgumentException("caseId required");
        }

        ruleNames = (ruleNames == null) ? List.of() : List.copyOf(ruleNames);
    }
}
