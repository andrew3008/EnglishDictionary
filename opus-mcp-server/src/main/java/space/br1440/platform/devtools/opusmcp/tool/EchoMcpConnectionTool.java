package space.br1440.platform.devtools.opusmcp.tool;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Phase 0A connectivity-validation tool: {@code echo_mcp_connection}.
 *
 * <p>This tool is intentionally trivial and TEMPORARY. Its only purpose is to prove that Cursor
 * can discover (tools/list) and invoke (tools/call) a Java MCP stdio server.
 *
 * <p>Hard boundaries (Phase 0A): it does NOT read files, write files, run commands, or call the
 * network. It only echoes the provided message. This is pure logic, decoupled from the MCP SDK so
 * it can be unit-tested without any transport.
 */
public final class EchoMcpConnectionTool {

    public static final String TOOL_NAME = "echo_mcp_connection";
    public static final String SERVER_NAME = "java-mcp-opus-server";
    public static final String PHASE = "0A";

    public static final String DESCRIPTION =
            "Connectivity validation tool (Phase 0A). Echoes the provided message back. "
                    + "Does not read or write files, run commands, or call any network. "
                    + "Temporary: used only to verify Cursor <-> Java MCP stdio connectivity.";

    /** Minimal JSON Schema for the tool input. */
    public static final String INPUT_SCHEMA_JSON = """
            {
              "type": "object",
              "properties": {
                "message": {
                  "type": "string",
                  "description": "Arbitrary text to be echoed back for connectivity validation."
                }
              },
              "additionalProperties": false
            }
            """;

    private final ObjectMapper objectMapper;

    public EchoMcpConnectionTool() {
        this(new ObjectMapper());
    }

    public EchoMcpConnectionTool(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /** Structured echo result. */
    public record EchoResult(String status, String echo, String server, String phase) {
    }

    /**
     * Pure handler. Null or blank messages are handled safely (echoed as an empty string).
     */
    public EchoResult handle(String message) {
        String echo = message == null ? "" : message;
        return new EchoResult("OK", echo, SERVER_NAME, PHASE);
    }

    /** Serializes the echo result to a stable JSON string for the MCP text content payload. */
    public String handleAsJson(String message) {
        EchoResult result = handle(message);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("status", result.status());
        payload.put("echo", result.echo());
        payload.put("server", result.server());
        payload.put("phase", result.phase());
        try {
            return objectMapper.writeValueAsString(payload);
        } catch (JsonProcessingException e) {
            // Should not happen for a plain string map; fail safe with a minimal JSON object.
            return "{\"status\":\"OK\",\"echo\":\"\",\"server\":\"" + SERVER_NAME + "\",\"phase\":\"" + PHASE + "\"}";
        }
    }
}
