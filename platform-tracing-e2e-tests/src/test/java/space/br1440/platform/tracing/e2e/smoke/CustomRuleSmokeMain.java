package space.br1440.platform.tracing.e2e.smoke;

import io.opentelemetry.api.trace.Span;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration"
})
public class CustomRuleSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: CustomRuleSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(CustomRuleSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=true",
                "--logging.level.root=INFO");

        CountDownLatch served = context.getBean(CountDownLatch.class);

        System.out.println(AgentHttpSpringSmokeProcessRunner.READY_MARKER);
        System.out.flush();

        try {
            if (!served.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Внешний HTTP-запрос к /probe не получен за 60 с");
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

    @RestController
    static class Controller {
        private final CountDownLatch servedLatch;

        Controller(CountDownLatch servedLatch) {
            this.servedLatch = servedLatch;
        }

        @GetMapping("/probe")
        String probe() {
            Span.current().setAttribute("e2e.custom.marker", "my-super-secret-value");
            servedLatch.countDown();
            return "ok";
        }
    }
}
