package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;

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
        "org.springframework.boot.autoconfigure.web.servlet.WebMvcAutoConfiguration"
})
@Import(ReactorPropagationSmokeController.class)
public class AgentWebFluxReactorPropagationSmokeMain {

    /** Маркер готовности для {@link space.br1440.platform.tracing.e2e.support.AgentWebFluxProcessRunner}. */
    public static final String READY_MARKER = "READY";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: AgentWebFluxReactorPropagationSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(AgentWebFluxReactorPropagationSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.web-application-type=reactive",
                "--spring.main.banner-mode=off",
                "--logging.level.root=WARN");

        CountDownLatch served = context.getBean(CountDownLatch.class);

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
        return new CountDownLatch(1);
    }
}
