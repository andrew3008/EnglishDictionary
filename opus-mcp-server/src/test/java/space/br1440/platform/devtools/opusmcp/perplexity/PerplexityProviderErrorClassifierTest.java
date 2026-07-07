package space.br1440.platform.devtools.opusmcp.perplexity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PerplexityProviderErrorClassifierTest {

    @Test
    void classifies2xxByParseResult() {
        assertThat(PerplexityProviderErrorClassifier.classify(200, true, "{}"))
                .isEqualTo(PerplexityDiagnosticCategory.OK);
        assertThat(PerplexityProviderErrorClassifier.classify(200, false, "{}"))
                .isEqualTo(PerplexityDiagnosticCategory.RESPONSE_PARSE_ERROR);
    }

    @Test
    void classifiesAuthErrors() {
        assertThat(PerplexityProviderErrorClassifier.classify(401, false, ""))
                .isEqualTo(PerplexityDiagnosticCategory.AUTH_ERROR);
        assertThat(PerplexityProviderErrorClassifier.classify(403, false, ""))
                .isEqualTo(PerplexityDiagnosticCategory.AUTH_ERROR);
    }

    @Test
    void classifiesModelNotFoundFrom400And404WhenBodyMentionsModel() {
        assertThat(PerplexityProviderErrorClassifier.classify(400, false, "Invalid model 'sonar-x'"))
                .isEqualTo(PerplexityDiagnosticCategory.MODEL_NOT_FOUND);
        assertThat(PerplexityProviderErrorClassifier.classify(404, false, "model not found"))
                .isEqualTo(PerplexityDiagnosticCategory.MODEL_NOT_FOUND);
    }

    @Test
    void classifiesRequestShapeWhenNoModelMention() {
        assertThat(PerplexityProviderErrorClassifier.classify(400, false, "bad json"))
                .isEqualTo(PerplexityDiagnosticCategory.REQUEST_SHAPE_ERROR);
        assertThat(PerplexityProviderErrorClassifier.classify(404, false, "not found"))
                .isEqualTo(PerplexityDiagnosticCategory.REQUEST_SHAPE_ERROR);
    }

    @Test
    void classifiesRateLimit() {
        assertThat(PerplexityProviderErrorClassifier.classify(429, false, ""))
                .isEqualTo(PerplexityDiagnosticCategory.RATE_LIMIT_OR_QUOTA);
    }

    @Test
    void classifiesProviderDownFor5xx() {
        for (int code : new int[] {500, 502, 503, 504, 599}) {
            assertThat(PerplexityProviderErrorClassifier.classify(code, false, ""))
                    .as("status %d", code)
                    .isEqualTo(PerplexityDiagnosticCategory.PROVIDER_DOWN);
        }
    }

    @Test
    void classifiesTimeoutAndNetworkFailure() {
        assertThat(PerplexityProviderErrorClassifier.classify(408, false, ""))
                .isEqualTo(PerplexityDiagnosticCategory.NETWORK_ERROR);
        assertThat(PerplexityProviderErrorClassifier.classifyNetworkFailure())
                .isEqualTo(PerplexityDiagnosticCategory.NETWORK_ERROR);
    }

    @Test
    void classifiesUnknownClientErrorAsUnknown() {
        assertThat(PerplexityProviderErrorClassifier.classify(418, false, ""))
                .isEqualTo(PerplexityDiagnosticCategory.UNKNOWN_PROVIDER_ERROR);
    }
}
