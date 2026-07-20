package space.br1440.platform.tracing.e2e.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import io.opentelemetry.api.GlobalOpenTelemetry;
import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.context.Scope;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.SdkMode;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;

@SpringBootConfiguration
@EnableAutoConfiguration
public class SpringAgentCompositionProbeMain {

    private static final String PREFIX = "SPRING_AGENT_COMPOSITION:";
    private static final String BOOT_OTEL_SDK_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration");

    public static void main(String[] args) {
        verifyAgentOwnedMode();
        verifyDisabledFacadeMode();
        emit("COMPLETE");
    }

    private static void verifyAgentOwnedMode() {
        try (ConfigurableApplicationContext context = startContext(SdkMode.AUTO)) {
            SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
            TraceOperations traceOperations = context.getBean(TraceOperations.class);

            emit("auto.runtimeState=" + diagnostics.runtimeState());
            if (diagnostics.extensionDescriptor() != null) {
                emit("auto.extensionLifecycle=" + diagnostics.extensionDescriptor().lifecycle());
                emit("auto.extensionCapabilities=" + diagnostics.extensionDescriptor().capabilities());
            }

            require(diagnostics.mode() == SdkMode.AGENT, "AUTO должен разрешаться в AGENT");
            require(diagnostics.agentDetected(), "agent marker должен быть обнаружен");
            require(!(traceOperations instanceof NoopTraceOperations), "AGENT facade не должен быть no-op");
            require(context.getBeansOfType(OpenTelemetry.class).isEmpty(),
                    "Spring не должен регистрировать второй OpenTelemetry bean");

            Span span = GlobalOpenTelemetry.getTracer("spring-agent-composition-e2e")
                    .spanBuilder("application-context-visibility")
                    .startSpan();
            try (Scope ignored = span.makeCurrent()) {
                String expectedTraceId = span.getSpanContext().getTraceId();
                String actualTraceId = traceOperations.traceContext().traceId().orElse(null);
                require(expectedTraceId.equals(actualTraceId),
                        "application facade должен видеть agent-owned current context");
                emit("auto.currentContextVisible=true");
            } finally {
                span.end();
            }

            emit("auto.mode=" + diagnostics.mode());
            emit("auto.agentDetected=" + diagnostics.agentDetected());
            emit("auto.facadeNoop=false");
            emit("auto.openTelemetryBeans=0");
        }
    }

    private static void verifyDisabledFacadeMode() {
        try (ConfigurableApplicationContext context = startContext(SdkMode.DISABLED)) {
            SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
            TraceOperations traceOperations = context.getBean(TraceOperations.class);

            require(diagnostics.mode() == SdkMode.DISABLED, "явный DISABLED должен сохраняться");
            require(diagnostics.agentDetected(), "agent marker должен оставаться видимым");
            require(traceOperations instanceof NoopTraceOperations, "DISABLED facade должен быть no-op");
            require(context.getBeansOfType(OpenTelemetry.class).isEmpty(),
                    "DISABLED Spring не должен регистрировать OpenTelemetry bean");

            Span agentSpan = GlobalOpenTelemetry.getTracer("spring-agent-composition-e2e")
                    .spanBuilder("agent-remains-active")
                    .startSpan();
            try {
                require(agentSpan.getSpanContext().isValid(),
                        "agent instrumentation должен оставаться активным при disabled facade");
            } finally {
                agentSpan.end();
            }

            emit("disabled.mode=" + diagnostics.mode());
            emit("disabled.agentDetected=" + diagnostics.agentDetected());
            emit("disabled.facadeNoop=true");
            emit("disabled.agentSpanValid=true");
            emit("disabled.openTelemetryBeans=0");
        }
    }

    private static ConfigurableApplicationContext startContext(SdkMode mode) {
        SpringApplication application = new SpringApplication(SpringAgentCompositionProbeMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--spring.main.banner-mode=off",
                // Общий E2E classpath содержит SDK для других тестов; production starter его не публикует.
                "--spring.autoconfigure.exclude=" + BOOT_OTEL_SDK_AUTO_CONFIGURATIONS,
                "--logging.level.root=WARN",
                "--platform.tracing.sdk.mode=" + mode.name());
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new IllegalStateException(message);
        }
    }

    private static void emit(String value) {
        System.out.println(PREFIX + value);
        System.out.flush();
    }
}
