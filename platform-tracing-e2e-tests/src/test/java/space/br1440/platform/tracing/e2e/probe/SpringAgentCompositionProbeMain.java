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
import space.br1440.platform.tracing.autoconfigure.agent.TracingDeploymentDiagnostics;
import space.br1440.platform.tracing.otel.facade.NoopTraceOperations;
import space.br1440.platform.tracing.otel.runtime.TracingRuntime;
import space.br1440.platform.tracing.otel.runtime.state.TracingRuntimeMode;

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
        verifyEnabledTracingUsesAgent();
        verifyDisabledTracingRejectsLiveAgent();
        emit("COMPLETE");
    }

    private static void verifyEnabledTracingUsesAgent() {
        try (ConfigurableApplicationContext context = startContext(true)) {
            TracingDeploymentDiagnostics diagnostics = context.getBean(TracingDeploymentDiagnostics.class);
            TraceOperations traceOperations = context.getBean(TraceOperations.class);

            emit("agent.runtimeState=" + diagnostics.runtimeState());
            if (diagnostics.extensionDescriptor() != null) {
                emit("agent.extensionLifecycle=" + diagnostics.extensionDescriptor().lifecycle());
                emit("agent.extensionCapabilities=" + diagnostics.extensionDescriptor().capabilities());
            }

            require(diagnostics.tracingEnabled(), "tracing должен быть включён");
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
                emit("agent.currentContextVisible=true");
            } finally {
                span.end();
            }

            emit("agent.tracingEnabled=" + diagnostics.tracingEnabled());
            emit("agent.agentDetected=" + diagnostics.agentDetected());
            emit("agent.facadeNoop=false");
            emit("agent.openTelemetryBeans=0");
        }
    }

    private static void verifyDisabledTracingRejectsLiveAgent() {
        try (ConfigurableApplicationContext ignored = startContext(false)) {
            throw new IllegalStateException("enabled=false не должен запускаться с живым Agent");
        } catch (RuntimeException expected) {
            String message = rootMessage(expected);
            require(message.contains("AGENT_READY"),
                    "enabled=false должен явно отклонить живой Controlled Agent: " + message);
            emit("disabledTracing.liveAgentRejected=true");
        }
    }

    private static ConfigurableApplicationContext startContext(boolean tracingEnabled) {
        SpringApplication application = new SpringApplication(SpringAgentCompositionProbeMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--spring.main.banner-mode=off",
                // Общий E2E classpath содержит SDK для других тестов; production starter его не публикует.
                "--spring.autoconfigure.exclude=" + BOOT_OTEL_SDK_AUTO_CONFIGURATIONS,
                "--logging.level.root=WARN",
                "--platform.tracing.enabled=" + tracingEnabled);
    }

    private static String rootMessage(Throwable failure) {
        Throwable current = failure;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
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
