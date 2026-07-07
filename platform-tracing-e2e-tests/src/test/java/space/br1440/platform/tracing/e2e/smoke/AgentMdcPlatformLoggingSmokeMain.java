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
 * Дочерний main для G2-MDC-e2e: Spring MVC + platform-tracing + platform-logging + OTel Agent.
 */
@SpringBootConfiguration
@EnableAutoConfiguration
@Import(MdcLoggingSmokeController.class)
public class AgentMdcPlatformLoggingSmokeMain {

    /** Маркер готовности для {@link space.br1440.platform.tracing.e2e.support.AgentMdcLoggingProcessRunner}. */
    public static final String READY_MARKER = "READY";

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: AgentMdcPlatformLoggingSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(AgentMdcPlatformLoggingSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--spring.application.name=mdc-platform-logging-e2e");

        CountDownLatch served = context.getBean(CountDownLatch.class);

        System.out.println(READY_MARKER);
        System.out.flush();

        try {
            if (!served.await(60, TimeUnit.SECONDS)) {
                throw new IllegalStateException("HTTP-запрос к /mdc-test не получен за 60 с");
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
