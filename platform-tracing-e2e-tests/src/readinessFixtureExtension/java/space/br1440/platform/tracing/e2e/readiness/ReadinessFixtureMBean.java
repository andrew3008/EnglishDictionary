package space.br1440.platform.tracing.e2e.readiness;

public interface ReadinessFixtureMBean {

    String getExtensionVersion();

    int getProtocolVersion();

    String getProfile();

    String getLifecycleState();

    String getFailureCode();

    String getFailureMessage();

    String[] getCapabilities();

    boolean isSanitizerInstalled();

    boolean isSamplerInstalled();

    boolean isRequiredSpanProcessorsInstalled();

    boolean isPropagationHooksInstalled();

    boolean isExportPathProtected();
}
