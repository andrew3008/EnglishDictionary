package space.br1440.platform.tracing.otel.javaagent.diagnostics;

/**
 * Сброс регистрации принятой политики между тестами.
 */
public final class PlatformLogControlTestSupport {

    private PlatformLogControlTestSupport() {
    }

    public static void reset() {
        PlatformLogControl.reset();
    }
}
