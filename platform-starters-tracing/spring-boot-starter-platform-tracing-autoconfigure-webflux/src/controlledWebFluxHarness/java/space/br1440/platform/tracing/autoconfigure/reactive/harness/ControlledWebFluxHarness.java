package space.br1440.platform.tracing.autoconfigure.reactive.harness;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.web.context.WebServerInitializedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.Bean;

import io.opentelemetry.api.GlobalOpenTelemetry;

@SpringBootApplication
public class ControlledWebFluxHarness {

    public static void main(String[] args) {
        SpringApplication.run(ControlledWebFluxHarness.class, args);
    }

    @Bean
    ApplicationListener<WebServerInitializedEvent> ready() {
        return event -> {
            boolean applicationSdkVisible;
            try {
                Class.forName(
                        "io.opentelemetry.sdk.OpenTelemetrySdk",
                        false,
                        ControlledWebFluxHarness.class.getClassLoader()
                );
                applicationSdkVisible = true;
            } catch (ClassNotFoundException absent) {
                applicationSdkVisible = false;
            }

            String tracerProvider = GlobalOpenTelemetry.get().getTracerProvider().getClass().getName();
            System.out.println("R1_SDK applicationSdkVisible=" + applicationSdkVisible
                    + " tracerProvider=" + tracerProvider);
            System.out.println("R1_READY port=" + event.getWebServer().getPort());
            System.out.flush();
        };
    }
}
