package space.br1440.platform.tracing.autoconfigure.diagnostics.health.startup.archfixture.invalid;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;

/**
 * Негативная фикстура Rule C: прямая зависимость на JMX-транспорт из пакета
 * {@code diagnostics.health.startup}. Физически размещена внутри package pattern production-правил
 * ({@code ..autoconfigure.diagnostics.health.startup..}), поэтому неизменённое правило действительно её
 * выбирает; {@code ImportOption.DoNotIncludeTests} на production {@code @AnalyzeClasses} не даёт этой
 * фикстуре просочиться в основную проверку.
 */
final class DependsOnPlatformTracingJmxClient {

    private final PlatformTracingJmxClient client;

    DependsOnPlatformTracingJmxClient(PlatformTracingJmxClient client) {
        this.client = client;
    }
}
