package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

/**
 * Classloader-neutral JMX-контракт готовности platform Java Agent extension.
 */
public interface PlatformExtensionReadinessMBean {

    String getExtensionVersion();

    int getProtocolVersion();

    String getProfile();

    String getLifecycleState();

    String getFailureCode();

    String getFailureMessage();

    String[] getCapabilities();

    String[] getRequiredCapabilities();

    boolean isSanitizerInstalled();

    boolean isSamplerInstalled();

    boolean isRequiredSpanProcessorsInstalled();

    boolean isPropagationHooksInstalled();

    boolean isExportPathProtected();
}
