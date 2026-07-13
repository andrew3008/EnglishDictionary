package space.br1440.platform.tracing.e2e.smoke;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.ConfigurableApplicationContext;

import space.br1440.platform.tracing.e2e.support.AgentHttpSpringSmokeProcessRunner;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import java.lang.management.ManagementFactory;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Дочерний main для e2e smoke force-sampling: Spring Boot + embedded Tomcat + Agent spring-webmvc.
 * <p>
 * HTTP-запрос с {@code X-Trace-On} приходит из JUnit (OkHttp). После обработки — flush BSP и shutdown.
 * Контракт READY/args см. {@link AgentHttpSpringSmokeProcessRunner}.
 */
@SpringBootConfiguration
@EnableAutoConfiguration(excludeName = {
        "space.br1440.platform.logging.configuration.LoggingAutoConfiguration",
        "space.br1440.platform.logging.configuration.GrpcLoggingConfiguration"
})
@Import(ProbeSmokeController.class)
public class AgentSpringForceSamplingSmokeMain {

    public static void main(String[] args) throws Exception {
        if (args.length < 2) {
            throw new IllegalArgumentException("Usage: AgentSpringForceSamplingSmokeMain <port> <flushDelayMs>");
        }
        int port = Integer.parseInt(args[0]);
        long flushDelayMs = Long.parseLong(args[1]);

        SpringApplication application = new SpringApplication(AgentSpringForceSamplingSmokeMain.class);
        application.setRegisterShutdownHook(false);
        String suppressMicrometerTracing = System.getProperty(
                "platform.tracing.suppression.suppress-micrometer-tracing", "true");
        ConfigurableApplicationContext context = application.run(
                "--server.port=" + port,
                "--spring.main.banner-mode=off",
                "--platform.tracing.suppression.suppress-micrometer-tracing=" + suppressMicrometerTracing,
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
            printSamplerDiagnostics();
        } finally {
            context.close();
        }
    }

    @Bean
    CountDownLatch servedLatch() {
        return new CountDownLatch(1);
    }

    private static void printSamplerDiagnostics() {
        try {
            MBeanServer server = ManagementFactory.getPlatformMBeanServer();
            ObjectName name = new ObjectName(
                    "space.br1440.platform.tracing:type=SamplingControl,name=PlatformSamplingControl");
            System.out.println("SMOKE_PROPAGATORS=" + System.getProperty("otel.propagators"));
            System.out.println("SMOKE_SAMPLER=" + System.getProperty("otel.traces.sampler"));
            System.out.println("SMOKE_CAPTURE_HEADERS="
                    + System.getProperty("otel.instrumentation.http.server.capture-request-headers"));
            System.out.println("SMOKE_SAMPLING_RATIO=" + System.getProperty("platform.tracing.sampling.ratio"));
            System.out.println("SMOKE_SAMPLING_MBEAN_REGISTERED=" + server.isRegistered(name));
            if (server.isRegistered(name)) {
                System.out.println("SMOKE_SAMPLER_DECISIONS="
                        + server.getAttribute(name, "SamplerDecisionCounts"));
            }
        } catch (Exception e) {
            System.out.println("SMOKE_SAMPLER_DIAGNOSTICS_FAILED=" + e);
        }
        System.out.flush();
    }
}
