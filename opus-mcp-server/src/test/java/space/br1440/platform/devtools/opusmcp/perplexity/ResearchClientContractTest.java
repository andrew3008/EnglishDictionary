package space.br1440.platform.devtools.opusmcp.perplexity;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 8D provider-independence contract for the research abstraction. Pins the small surface that
 * decouples {@code research_with_perplexity} from any concrete provider, so future live provider
 * changes cannot leak provider-specific shapes (raw body, headers, API key) into tool output.
 */
class ResearchClientContractTest {

    @Test
    void researchResponseIsProviderNeutralMetadataOnly() {
        ResearchResponse response = new ResearchResponse("answer text", 11, 22, "some-model", "rid-1");
        assertThat(response.text()).isEqualTo("answer text");
        assertThat(response.inputTokenEstimate()).isEqualTo(11);
        assertThat(response.outputTokenEstimate()).isEqualTo(22);
        assertThat(response.model()).isEqualTo("some-model");
        assertThat(response.requestId()).isEqualTo("rid-1");
        // The record exposes exactly 5 components: no field can carry a raw provider body or key.
        assertThat(ResearchResponse.class.getRecordComponents()).hasSize(5);
    }

    @Test
    void researchClientExceptionCarriesOnlySafeCategoryAndStatus() {
        ResearchClientException ex = new ResearchClientException(
                PerplexityDiagnosticCategory.AUTH_ERROR, 401, "safe summary");
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.AUTH_ERROR);
        assertThat(ex.httpStatus()).isEqualTo(401);
        assertThat(ex.getMessage()).isEqualTo("safe summary");
    }

    @Test
    void researchClientExceptionNormalizesNullCategory() {
        ResearchClientException ex = new ResearchClientException(null, -1, null);
        assertThat(ex.category()).isEqualTo(PerplexityDiagnosticCategory.UNKNOWN_PROVIDER_ERROR);
    }

    @Test
    void anyProviderImplementationSatisfiesTheInterface() throws ResearchClientException {
        // A non-Perplexity stub implementation must compile and flow through the same contract.
        ResearchClient stub = (system, user) ->
                new ResearchResponse("SUMMARY:\nok\nANSWER:\nstub", 1, 1, "stub-model", "stub-rid");
        ResearchResponse response = stub.research("sys", "user");
        assertThat(response.model()).isEqualTo("stub-model");
        assertThat(response.text()).contains("stub");
    }

    @Test
    void throwingProviderSurfacesAsResearchClientException() {
        ResearchClient failing = (system, user) -> {
            throw new ResearchClientException(PerplexityDiagnosticCategory.PROVIDER_DOWN, 503, "down");
        };
        assertThatThrownBy(() -> failing.research("s", "u"))
                .isInstanceOf(ResearchClientException.class);
    }
}
