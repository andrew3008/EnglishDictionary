package space.br1440.platform.tracing.otel.javaagent.jmx.readiness;

public interface PlatformExtensionReadinessMBean {

    String getExtensionVersion();

    int getProtocolVersion();

    String getProfile();

    String getLifecycleState();

    String getFailureCode();

    String getFailureMessage();

    String[] getCapabilities();

}
