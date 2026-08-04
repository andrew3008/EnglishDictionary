package space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.archfixture.invalid;

import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.SamplingJmxView;

/**
 * Негативная фикстура: зависимость на {@link SamplingJmxView} из подпакета вне корневого
 * {@code …diagnostics.jmx}. Используется только в fixture-proof тесте
 * {@code JmxPackageBoundaryArchTest}; production {@code @AnalyzeClasses} с
 * {@code DoNotIncludeTests} эту фикстуру не видит.
 */
public final class DependsOnSamplingJmxView {

    private final SamplingJmxView view;

    public DependsOnSamplingJmxView(SamplingJmxView view) {
        this.view = view;
    }
}
