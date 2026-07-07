package space.br1440.platform.devtools.opusmcp.tool;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EchoMcpConnectionToolTest {

    private final EchoMcpConnectionTool tool = new EchoMcpConnectionTool();

    @Test
    void returnsOkAndEchoesMessage() {
        EchoMcpConnectionTool.EchoResult result = tool.handle("hello");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.echo()).isEqualTo("hello");
        assertThat(result.server()).isEqualTo(EchoMcpConnectionTool.SERVER_NAME);
        assertThat(result.phase()).isEqualTo("0A");
    }

    @Test
    void handlesNullMessageSafely() {
        EchoMcpConnectionTool.EchoResult result = tool.handle(null);

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.echo()).isEmpty();
    }

    @Test
    void handlesBlankMessageSafely() {
        EchoMcpConnectionTool.EchoResult result = tool.handle("   ");

        assertThat(result.status()).isEqualTo("OK");
        assertThat(result.echo()).isEqualTo("   ");
    }

    @Test
    void serializesResultToJsonWithAllFields() {
        String json = tool.handleAsJson("ping");

        assertThat(json)
                .contains("\"status\":\"OK\"")
                .contains("\"echo\":\"ping\"")
                .contains("\"server\":\"" + EchoMcpConnectionTool.SERVER_NAME + "\"")
                .contains("\"phase\":\"0A\"");
    }

    @Test
    void jsonForNullMessageHasEmptyEcho() {
        String json = tool.handleAsJson(null);

        assertThat(json).contains("\"echo\":\"\"");
    }
}
