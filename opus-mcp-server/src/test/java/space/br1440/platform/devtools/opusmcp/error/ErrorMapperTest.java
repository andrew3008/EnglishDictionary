package space.br1440.platform.devtools.opusmcp.error;

import org.junit.jupiter.api.Test;
import space.br1440.platform.devtools.opusmcp.model.OpusClientException;
import space.br1440.platform.devtools.opusmcp.tool.dto.GenerateCodeStatus;

import static org.assertj.core.api.Assertions.assertThat;

class ErrorMapperTest {

    private final ErrorMapper mapper = new ErrorMapper();

    @Test
    void mapsAuthErrorsToModelError() {
        assertThat(mapper.mapHttpStatus(401)).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
        assertThat(mapper.mapHttpStatus(403)).isEqualTo(GenerateCodeStatus.MODEL_ERROR);
    }

    @Test
    void maps429ToBudgetExceeded() {
        assertThat(mapper.mapHttpStatus(429)).isEqualTo(GenerateCodeStatus.BUDGET_EXCEEDED);
    }

    @Test
    void safeMessagesDoNotMentionSecrets() {
        assertThat(mapper.safeMessageForHttpStatus(404)).contains("OPUS_BASE_URL");
        assertThat(mapper.safeMessageForException(
                new OpusClientException(OpusClientException.Reason.MISSING_API_KEY, "missing")))
                .contains("OPUS_API_KEY");
    }
}
