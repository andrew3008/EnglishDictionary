package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

import io.opentelemetry.api.OpenTelemetry;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Дочерний main для G2-05-e2e: Spring WebFlux + platform tracing starter + OTel Java Agent.
 * <p>
 * HTTP-запрос к {@code /propagation-test} приходит из JUnit (OkHttp). Эндпоинт выполняет
 * {@code publishOn(Schedulers.parallel())} и возвращает traceId с worker thread.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration",
        "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.opentelemetry.OpenTelemetryAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.OpenTelemetryLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.logging.otlp.OtlpLoggingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.OpenTelemetryTracingAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpAutoConfiguration",
        "org.springframework.boot.actuate.autoconfigure.tracing.otlp.OtlpTracingAutoConfiguration"
})
@Import(ReactorPropagationSmokeController.class)
public class AgentWebFluxReactorPropagationSmokeMain {

    /** Маркер готовности для {@link space.br1440.platform.tracing.e2e.support.AgentWebFluxProcessRunner}. */
    public static final String READY_MARKER = "READY";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException(
                    "Usage: AgentWebFluxReactorPropagationSmokeMain <port> <flushDelayMs> [requestCount]");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);
        int requestCount = args.length >= 3 ? Integer.parseInt(args[2]) : 1;
        System.setProperty("e2.webflux.request-count", Integer.toString(requestCount));

        SpringApplication application = new SpringApplication(AgentWebFluxReactorPropagationSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.web-application-type=reactive",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");

        CountDownLatch served = context.getBean(CountDownLatch.class);

        System.out.println("WEBFLUX_E2:openTelemetryBeans=" + context.getBeansOfType(OpenTelemetry.class).size());
        System.out.println(READY_MARKER);
        System.out.flush();

        try {
            if (!served.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("HTTP-запрос к /propagation-test не получен за 60 с");
            }
            Thread.sleep(flushDelayMs);
        } finally {
            context.close();
        }
    }

    @Bean
    CountDownLatch servedLatch() {
        return new CountDownLatch(Integer.getInteger("e2.webflux.request-count", 1));
    }
}
