package space.br1440.platform.tracing.otel.javaagent.distribution;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import org.junit.jupiter.api.Test;

class UpstreamAgentContractTest {

    private static final String ACP_PIN_RESOURCE = "META-INF/platform-tracing/otel-javaagent-acp-pin.properties";
    private static final String OTEL_SDK_VERSION_ENTRY = "inst/io/opentelemetry/sdk/version.properties";
    private static final String EXPECTED_PIN_FORMAT_VERSION = "1";

    @Test
    void pin_matches_the_resolved_otel_java_agent() throws IOException {
        // given
        Path otel = AgentArchive.requiredPath("platformTracing.otelJavaAgentJar");

        // when
        Properties pin = AgentArchive.loadProperties(
                AgentArchive.readNestedEntry(AgentArchive.requiredPath("platformTracing.extensionJar"), ACP_PIN_RESOURCE)
        );

        // then
        assertThat(pin.getProperty("format.version")).isEqualTo(EXPECTED_PIN_FORMAT_VERSION);
        assertThat(pin.getProperty("otel.javaagent.sha256"))
                .isEqualTo(AgentArchive.sha256Hex(Files.readAllBytes(otel)));
    }

    @Test
    void upstream_agent_sdk_version_matches_version_catalog() throws IOException {
        // given
        Path otel = AgentArchive.requiredPath("platformTracing.otelJavaAgentJar");
        String expectedSdkVersion = AgentArchive.requiredProperty("platformTracing.expectedOtelSdkVersion");

        // when
        Properties sdkVersion = AgentArchive.loadProperties(AgentArchive.readNestedEntry(otel, OTEL_SDK_VERSION_ENTRY));

        // then
        assertThat(sdkVersion.getProperty("sdk.version"))
                .as("OpenTelemetry SDK version embedded in the upstream javaagent")
                .isEqualTo(expectedSdkVersion);
    }
}
