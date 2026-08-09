package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

public final class PlatformExtensionReadinessControl implements PlatformExtensionReadinessMBean {

    private final PlatformExtensionReadiness readiness;

    public PlatformExtensionReadinessControl(PlatformExtensionReadiness readiness) {
        this.readiness = readiness;
    }

    @Override
    public String getExtensionVersion() {
        return readiness.extensionVersion();
    }

    @Override
    public int getProtocolVersion() {
        return PlatformExtensionReadiness.PROTOCOL_VERSION;
    }

    @Override
    public String getProfile() {
        return PlatformExtensionReadiness.PROFILE;
    }

    @Override
    public String getLifecycleState() {
        return readiness.lifecycle().name();
    }

    @Override
    public String getFailureCode() {
        return readiness.failureCode();
    }

    @Override
    public String getFailureMessage() {
        return readiness.failureMessage();
    }

    @Override
    public String[] getCapabilities() {
        return readiness.capabilities();
    }
}
