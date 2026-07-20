package space.br1440.platform.tracing.e2e.probe;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.support.GenericApplicationContext;

import io.opentelemetry.api.OpenTelemetry;
import io.opentelemetry.sdk.OpenTelemetrySdk;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;
import space.br1440.platform.tracing.core.facade.NoopTraceOperations;

@SpringBootConfiguration
@EnableAutoConfiguration
public class AgentExtensionAttestationProbeMain {

    private static final String PREFIX = "E1_ATTESTATION:";
    private static final String BOOT_OTEL_SDK_AUTO_CONFIGURATIONS = String.join(",",
            "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
            "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration");

    public static void main(String[] args) {
        AgentRuntimeState expectedState = AgentRuntimeState.valueOf(args[0]);
        String configuredMode = args.length > 1 ? args[1] : "AUTO";
        boolean dualRuntime = args.length > 2 && Boolean.parseBoolean(args[2]);

        try (ConfigurableApplicationContext context = start(configuredMode, dualRuntime)) {
            SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
            TraceOperations facade = context.getBean(TraceOperations.class);
            emit("runtimeState=" + diagnostics.runtimeState());
            emit("mode=" + diagnostics.mode());
            emit("facadeNoop=" + (facade instanceof NoopTraceOperations));
            emit("endpointPresent=" + diagnostics.extensionDescriptor().readinessEndpointPresent());
            emit("lifecycle=" + diagnostics.extensionDescriptor().lifecycle());
            emit("failureCode=" + diagnostics.extensionDescriptor().failureCode());
            require(diagnostics.runtimeState() == expectedState,
                    "Ожидалось состояние " + expectedState + ", получено " + diagnostics.runtimeState());
            emit("COMPLETE");
        } catch (RuntimeException failure) {
            AgentRuntimeState observed = findRuntimeState(failure);
            emit("startupFailure=" + rootMessage(failure));
            if (expectedState == AgentRuntimeState.DUAL_SDK_DETECTED
                    && rootMessage(failure).contains("OpenTelemetry bean and active Java Agent")) {
                emit("runtimeState=" + AgentRuntimeState.DUAL_SDK_DETECTED);
                emit("COMPLETE");
                return;
            }
            if (observed == expectedState) {
                emit("runtimeState=" + observed);
                emit("COMPLETE");
                return;
            }
            throw failure;
        }
    }

    private static ConfigurableApplicationContext start(String mode, boolean dualRuntime) {
        SpringApplication application = new SpringApplication(AgentExtensionAttestationProbeMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        if (dualRuntime) {
            application.addInitializers(context -> ((GenericApplicationContext) context).registerBean(
                    "applicationOpenTelemetry",
                    OpenTelemetry.class,
                    () -> OpenTelemetrySdk.builder().build()));
        }
        return application.run(
                "--spring.main.banner-mode=off",
                "--spring.autoconfigure.exclude=" + BOOT_OTEL_SDK_AUTO_CONFIGURATIONS,
                "--logging.level.root=ERROR",
                "--platform.tracing.sdk.mode=" + mode);
    }

    private static AgentRuntimeState findRuntimeState(Throwable failure) {
        String message = rootMessage(failure);
        if (message.contains("READY compatible platform Java Agent extension")) {
            return AgentRuntimeState.EXTENSION_MISSING;
        }
        return null;
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
