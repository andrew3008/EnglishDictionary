package space.br1440.platform.tracing.autoconfigure.diagnostics;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigureAfter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

import space.br1440.platform.tracing.autoconfigure.TracingRuntimeAutoConfiguration;
import space.br1440.platform.tracing.autoconfigure.diagnostics.jmx.PlatformTracingJmxClient;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;

/** Регистрирует read-only application diagnostics без зависимости от Actuator. */
@AutoConfiguration
@AutoConfigureAfter(TracingRuntimeAutoConfiguration.class)
public class TracingRuntimeDiagnosticsAutoConfiguration {

    @Bean
    @ConditionalOnBean(TracingRuntime.class)
    @ConditionalOnMissingBean
    public SpanFactoryDiagnostics spanFactoryDiagnostics(TracingRuntime tracingRuntime) {
        return new SpanFactoryDiagnostics(tracingRuntime);
    }

    @Bean
    @ConditionalOnMissingBean
    public PlatformTracingJmxClient platformTracingJmxClient() {
        return new PlatformTracingJmxClient();
    }
}
