package space.br1440.platform.tracing.e2e.probe;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;
import space.br1440.platform.tracing.api.spi.SpanAttributeScrubbingRule;

/**
 * Минимальный child-JVM entrypoint для E2E-теста {@code ClassLoaderVisibilityE2ETest}.
 * <p>
 * F1-probe выполняется автоматически через OTel extension SPI ({@code AutoConfigurationCustomizerProvider})
 * в {@code ExtensionClassLoader} агента — до входа в этот {@code main}.
 * Launcher только сигнализирует готовность.
 * <p>
 * Намеренно без зависимостей от Spring/OkHttp — для portable-запуска.
 */
public final class ClassLoaderVisibilityE2ELauncher {

    private ClassLoaderVisibilityE2ELauncher() {
    }

    public static void main(String[] args) {
        emit("applicationLauncherClassLoader=" + classLoaderName(
                ClassLoaderVisibilityE2ELauncher.class.getClassLoader()));
        emit("applicationApiClassLoader=" + classLoaderName(
                SpanAttributeScrubbingRule.class.getClassLoader()));
        emit("extensionProbeVisibleFromApplication=" + isClassVisible(
                "space.br1440.platform.tracing.e2e.probe.ClassLoaderVisibilityTestProbe",
                ClassLoaderVisibilityE2ELauncher.class.getClassLoader()));

        Span span = GlobalOpenTelemetry.getTracer("classloader-visibility-e2e")
                .spanBuilder("context-visibility")
                .startSpan();
        try (Scope ignored = span.makeCurrent()) {
            emit("applicationCurrentSpanValid=" + Span.current().getSpanContext().isValid());
            emit("applicationCurrentTraceId=" + Span.current().getSpanContext().getTraceId());
        } finally {
            span.end();
        }

        System.out.println("READY");
        System.out.flush();
    }

    private static String classLoaderName(ClassLoader loader) {
        if (loader == null) {
            return "bootstrap";
        }
        return loader.getClass().getName() + "@" + Integer.toHexString(System.identityHashCode(loader));
    }

    private static boolean isClassVisible(String className, ClassLoader loader) {
        try {
            Class.forName(className, false, loader);
            return true;
        } catch (ClassNotFoundException expected) {
            return false;
        }
    }

    private static void emit(String payload) {
        System.out.println("CL_VISIBILITY:" + payload);
    }
}
