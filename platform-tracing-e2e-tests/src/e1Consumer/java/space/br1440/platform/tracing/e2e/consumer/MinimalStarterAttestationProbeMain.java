package space.br1440.platform.tracing.e2e.consumer;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;

import space.br1440.platform.tracing.api.TraceOperations;
import space.br1440.platform.tracing.autoconfigure.support.AgentRuntimeState;
import space.br1440.platform.tracing.autoconfigure.support.SdkModeDiagnostics;

@SpringBootConfiguration
@EnableAutoConfiguration
public class MinimalStarterAttestationProbeMain {

    private static final String PREFIX = "E1_ATTESTATION:";

    public static void main(String[] args) {
        AgentRuntimeState expectedState = AgentRuntimeState.valueOf(args[0]);
        String configuredMode = args.length > 1 ? args[1] : "AUTO";

        try (ConfigurableApplicationContext context = start(configuredMode)) {
            SdkModeDiagnostics diagnostics = context.getBean(SdkModeDiagnostics.class);
            TraceOperations facade = context.getBean(TraceOperations.class);
            emit("runtimeState=" + diagnostics.runtimeState());
            emit("mode=" + diagnostics.mode());
            emit("facadeNoop=" + facade.getClass().getSimpleName().contains("Noop"));
            emit("endpointPresent=" + diagnostics.extensionDescriptor().readinessEndpointPresent());
            emit("lifecycle=" + diagnostics.extensionDescriptor().lifecycle());
            emit("failureCode=" + diagnostics.extensionDescriptor().failureCode());
            require(diagnostics.runtimeState() == expectedState,
                    "Ожидалось состояние " + expectedState + ", получено " + diagnostics.runtimeState());
            emit("COMPLETE");
        } catch (RuntimeException failure) {
            String message = rootMessage(failure);
            emit("startupFailure=" + message);
            if (expectedState == AgentRuntimeState.EXTENSION_MISSING
                    && message.contains("READY compatible platform Java Agent extension")) {
                emit("runtimeState=" + expectedState);
                emit("COMPLETE");
                return;
            }
            throw failure;
        }
    }

    private static ConfigurableApplicationContext start(String mode) {
        SpringApplication application = new SpringApplication(MinimalStarterAttestationProbeMain.class);
        application.setWebApplicationType(WebApplicationType.NONE);
        application.setRegisterShutdownHook(false);
        return application.run(
                "--spring.main.banner-mode=off",
                "--logging.level.root=ERROR",
                "--platform.tracing.sdk.mode=" + mode);
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
