package space.br1440.platform.tracing.e2e.readiness;

import java.util.List;

final class ReadinessFixture implements ReadinessFixtureMBean {

    private static final List<String> COMPLETE_CAPABILITIES = List.of(
            "CONFIGURATION_LOADED",
            "PLATFORM_SAMPLER_INSTALLED",
            "REQUIRED_SPAN_PROCESSORS_INSTALLED",
            "SANITIZER_INSTALLED",
            "PROPAGATION_HOOKS_INSTALLED",
            "SAFE_EXPORTER_INSTALLED",
            "EXPORT_PATH_PROTECTED");

    private final boolean complete = Boolean.getBoolean("e1.fixture.complete");

    @Override
    public String getExtensionVersion() {
        return System.getProperty("e1.fixture.extension.version", "development");
    }

    @Override
    public int getProtocolVersion() {
        return Integer.getInteger("e1.fixture.protocol.version", 1);
    }

    @Override
    public String getProfile() {
        return System.getProperty("e1.fixture.profile", "platform-agent-secure-v1");
    }

    @Override
    public String getLifecycleState() {
        return System.getProperty("e1.fixture.lifecycle", "INITIALIZING");
    }

    @Override
    public String getFailureCode() {
        return System.getProperty("e1.fixture.failure.code", "");
    }

    @Override
    public String getFailureMessage() {
        return System.getProperty("e1.fixture.failure.message", "");
    }

    @Override
    public String[] getCapabilities() {
        List<String> capabilities = complete
                ? COMPLETE_CAPABILITIES
                : List.of("CONFIGURATION_LOADED", "PLATFORM_SAMPLER_INSTALLED");
        return capabilities.toArray(String[]::new);
    }

    @Override
    public boolean isSanitizerInstalled() {
        return complete;
    }

    @Override
    public boolean isSamplerInstalled() {
        return true;
    }

    @Override
    public boolean isRequiredSpanProcessorsInstalled() {
        return complete;
    }

    @Override
    public boolean isPropagationHooksInstalled() {
        return complete;
    }

    @Override
    public boolean isExportPathProtected() {
        return complete;
    }
}
