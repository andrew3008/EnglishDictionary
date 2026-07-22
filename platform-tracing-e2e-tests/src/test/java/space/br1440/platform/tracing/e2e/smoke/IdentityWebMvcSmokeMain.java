package space.br1440.platform.tracing.e2e.smoke;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import io.opentelemetry.api.OpenTelemetry;

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration",
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration"
})
@Import(IdentityWebMvcSmokeController.class)
public class IdentityWebMvcSmokeMain {

    public static void main(String[] args) throws Exception {
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);
        SpringApplication application = new SpringApplication(IdentityWebMvcSmokeMain.class);
        application.setRegisterShutdownHook(false);

        try (ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=true",
                "--logging.level.root=WARN")) {
            System.out.println("IDENTITY_WEBMVC:openTelemetryBeans="
                    + context.getBeansOfType(OpenTelemetry.class).size());
            System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
            System.out.flush();

            if (!context.getBean(CountDownLatch.class).await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Запрос /identity не получен за 60 секунд");
            }
            Thread.sleep(flushDelayMs);
        }
    }

    @Bean
    CountDownLatch servedLatch() {
        return new CountDownLatch(1);
    }
}
