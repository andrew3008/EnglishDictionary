package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionCapability;
import space.br1440.platform.tracing.otel.javaagent.readiness.PlatformExtensionReadiness;

/**
 * JMX-проекция extension-local состояния без передачи объектов между classloader'ами.
 */
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

    @Override
    public String[] getRequiredCapabilities() {
        return readiness.requiredCapabilities();
    }

    @Override
    public boolean isSanitizerInstalled() {
        return readiness.has(PlatformExtensionCapability.SANITIZER_INSTALLED);
    }

    @Override
    public boolean isSamplerInstalled() {
        return readiness.has(PlatformExtensionCapability.PLATFORM_SAMPLER_INSTALLED);
    }

    @Override
    public boolean isRequiredSpanProcessorsInstalled() {
        return readiness.has(PlatformExtensionCapability.REQUIRED_SPAN_PROCESSORS_INSTALLED);
    }

    @Override
    public boolean isPropagationHooksInstalled() {
        return readiness.has(PlatformExtensionCapability.PROPAGATION_HOOKS_INSTALLED);
    }

    @Override
    public boolean isExportPathProtected() {
        return readiness.has(PlatformExtensionCapability.EXPORT_PATH_PROTECTED);
    }
}
