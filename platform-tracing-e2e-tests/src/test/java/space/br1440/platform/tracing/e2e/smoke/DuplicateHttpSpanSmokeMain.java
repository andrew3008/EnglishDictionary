package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Дочерний main для DupSpans HTTP smoke: Spring Boot + Tomcat + Agent spring-webmvc.
 *
 * <p><b>Намеренно сломанная конфигурация:</b>
 * {@code platform.tracing.suppression.suppress-micrometer-tracing=false} при подключённом
 * Agent — позволяет наблюдать дублирующиеся HTTP-span'ы (Agent + Micrometer Observation)
 * на одном HTTP-route. Требует {@code micrometer-tracing-bridge-otel} на classpath
 * (dev/staging path, см. e2e build.gradle).</p>
 *
 * <p>Используем {@code @SpringBootConfiguration}, а не {@code @SpringBootApplication}, чтобы
 * не подхватить другие smoke-main классы из того же пакета (конфликт bean {@code servedLatch}).</p>
 *
 * <p>Контракт READY/args см. {@link AgentHttpSpringSmokeProcessRunner}.</p>
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration"
})
@Import(ProbeSmokeController.class)
public class DuplicateHttpSpanSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: DuplicateHttpSpanSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(DuplicateHttpSpanSmokeMain.class);
        application.setRegisterShutdownHook(false);
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                // Намеренно false: воспроизводим дублирование Agent + Micrometer.
                "--platform.tracing.suppression.suppress-micrometer-tracing=false",
                // bridge-otel path: Micrometer Observation должна экспортировать span'ы в OTel/Jaeger.
                "--management.tracing.enabled=true",
                "--logging.level.root=WARN");

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
}
