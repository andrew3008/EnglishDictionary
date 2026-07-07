package space.br1440.platform.tracing.e2e.probe;

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
        System.out.println("READY");
        System.out.flush();
    }
}
